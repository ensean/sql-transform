package com.aws.analytics.util

import java.util.Properties
import java.util.regex._
import com.aws.analytics.config.DBConfig
import com.aws.analytics.config.Configurations.DBConfiguration
import com.aws.analytics.config.InternalConfs.{IncrementalSettings, InternalConfig, TableDetails,DBField}
import com.aws.analytics.config.RedshiftType
import org.slf4j.{Logger, LoggerFactory}

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import scala.collection.immutable.{IndexedSeq, Seq, Set}

/*
--packages "org.apache.hadoop:hadoop-aws:2.7.2,com.databricks:spark-redshift_2.10:1.1.0,com.amazonaws:aws-java-sdk:1.7.4,mysql:mysql-connector-java:5.1.39"
--jars=<Some-location>/RedshiftJDBC4-1.1.17.1017.jar
*/


class MaxComputeUtil extends DBEngineUtil {
    private val logger: Logger = LoggerFactory.getLogger("MaxComputeUtil")
    private val MySQL_CLASS_NAME = "com.aliyun.odps.jdbc.OdpsDriver"

    def getJDBCUrl(conf: DBConfig): String = {
        s"jdbc:odps:${conf.hostname}?project=${conf.database}&useProjectTimeZone=true"
    }

    def queryByJDBC(conf: DBConfig, sql: String) : Seq[String] = {
        var conn: Connection = null
        var ps: PreparedStatement = null
        var rs: ResultSet = null
        var seq: Seq[String] = Seq()
        try {
            Class.forName(MySQL_CLASS_NAME)
            conn = DriverManager.getConnection(getJDBCUrl(conf), conf.userName, conf.password)
            ps = conn.prepareStatement(sql)
            rs = ps.executeQuery

            while (rs.next)
                seq :+= rs.getString(2)     // maxcompute show tables 包含 owner 信息
            seq
        } catch {
            case e: Exception => e.printStackTrace
                seq
        } finally {
            if (rs != null) rs.close
            if (ps != null) ps.close
            if (conn != null) conn.close
        }
    }

    def getConnection(conf: DBConfig): Connection = {
        val connectionProps = new Properties()
        //connectionProps.put("user", conf.userName)
        //connectionProps.put("password", conf.password)
        val connectionString = getJDBCUrl(conf)
        println(s"connection string: = ${connectionString}" )
        Class.forName(MySQL_CLASS_NAME)
        DriverManager.getConnection(connectionString, conf.userName, conf.password)
    }

    //Use this method to get the columns to extract
    def getValidFieldNames(mysqlConfig: DBConfig, internalConfig: InternalConfig)(implicit crashOnInvalidType: Boolean): TableDetails = {
        val conn = getConnection(mysqlConfig)
        val tableDetails = getTableDetails(conn, mysqlConfig, internalConfig)(crashOnInvalidType)
        conn.close()
        tableDetails
    }

    def getTableDetails(conn: Connection, conf: DBConfig, internalConfig: InternalConfig)
                       (implicit crashOnInvalidType: Boolean): TableDetails = {
        val stmt = conn.createStatement()
        // TODO 需要识别分区键
        val query = s"set odps.sql.allow.fullscan=true; SELECT * from ${conf.database}.${conf.tableName} where 1 < 0"
        val rs = stmt.executeQuery(query)
        val rsmd = rs.getMetaData
        val validFieldTypes = mysqlToRedshiftTypeConverter.keys.toSet
        var validFields = Seq[DBField]()
        var invalidFields = Seq[DBField]()

        var setColumns = scala.collection.immutable.Set[String]()

        for (i <- 1 to rsmd.getColumnCount) {
            val columnType = rsmd.getColumnTypeName(i)
            val precision = rsmd.getPrecision(i)
            val scale = rsmd.getScale(i)

            if (validFieldTypes.contains(columnType.toUpperCase)) {
                val redshiftColumnType = convertMySqlTypeToRedshiftType(columnType, precision, scale)
                val javaTypeMapping = {
                    if (redshiftColumnType == "TIMESTAMP" || redshiftColumnType == "DATE") Some("String")
                    else None
                }
                validFields = validFields :+ DBField(rsmd.getColumnName(i), redshiftColumnType, javaTypeMapping)

            } else {
                if (crashOnInvalidType)
                    throw new IllegalArgumentException(s"Invalid type $columnType")
                invalidFields = invalidFields :+ DBField(rsmd.getColumnName(i), columnType)
            }
            setColumns = setColumns + rsmd.getColumnName(i).toLowerCase
            logger.info(s" column: ${rsmd.getColumnName(i)}, type: ${rsmd.getColumnTypeName(i)}," +
              s" precision: ${rsmd.getPrecision(i)}, scale:${rsmd.getScale(i)}\n")
        }
        rs.close()
        stmt.close()
        val sortKeys = getIndexes(conn, setColumns, conf)
        val distKey = getDistStyleAndKey(conn, setColumns, conf, internalConfig)
        val primaryKey = getPrimaryKey(conn, setColumns, conf)
        TableDetails(validFields, invalidFields, sortKeys, distKey, primaryKey)
    }

    def convertMySqlTypeToRedshiftType(columnType: String, precision: Int, scale: Int): String = {
        val redshiftType: RedshiftType = mysqlToRedshiftTypeConverter(columnType.toUpperCase)
        //typeName:String, hasPrecision:Boolean = false, hasScale:Boolean = false, precisionMultiplier:Int
        val result = if (redshiftType.hasPrecision && redshiftType.hasScale) {
            s"${redshiftType.typeName}(${precision * redshiftType.precisionMultiplier}, $scale)"
        } else if (redshiftType.hasPrecision) {
            var redshiftPrecision = precision * redshiftType.precisionMultiplier
            if (redshiftPrecision < 0 || redshiftPrecision > 65535)
                redshiftPrecision = 65535
            s"${redshiftType.typeName}($redshiftPrecision)"
        } else {
            redshiftType.typeName
        }
        logger.info(s"Converted type: $columnType, precision: $precision, scale:$scale to $result")
        result
    }

    def getIndexes(con: Connection, setColumns: Set[String], conf: DBConfig): IndexedSeq[String] = {
        val meta = con.getMetaData
        val resIndexes = meta.getIndexInfo(conf.database, null, conf.tableName, false, false)
        var setIndexedColumns = scala.collection.immutable.Set[String]()
        while (resIndexes.next) {
            val columnName = resIndexes.getString(9)
            if (setColumns.contains(columnName.toLowerCase)) {
                setIndexedColumns = setIndexedColumns + columnName
            } else {
                System.err.println(s"Rejected $columnName")
            }
        }
        resIndexes.close()
        // Redshift can only have 8 interleaved sort keys
        setIndexedColumns.toIndexedSeq.take(2)
    }


    def getDistStyleAndKey(con: Connection, setColumns: Set[String], conf: DBConfig, internalConfig: InternalConfig): Option[String] = {
        internalConfig.distKey match {
            case Some(key) =>
                logger.info("Found distKey in configuration {}", key)
                Some(key)
            case None =>
                logger.info("Found no distKey in configuration")
                val meta = con.getMetaData
                val resPrimaryKeys = meta.getPrimaryKeys(conf.database, null, conf.tableName)
                var primaryKeys = scala.collection.immutable.Set[String]()

                while (resPrimaryKeys.next) {
                    val columnName = resPrimaryKeys.getString(4)
                    if (setColumns.contains(columnName.toLowerCase)) {
                        primaryKeys = primaryKeys + columnName
                    } else {
                        logger.warn(s"Rejected $columnName")
                    }
                }

                resPrimaryKeys.close()
                if (primaryKeys.size != 1) {
                    logger.error(s"Found multiple or zero primary keys, Not taking any. ${primaryKeys.mkString(",")}")
                    None
                } else {
                    logger.info(s"Found primary keys, distribution key is. ${primaryKeys.toSeq.head}")
                    Some(primaryKeys.toSeq.head)
                }
        }
    }

    def getPrimaryKey(con: Connection, setColumns: Set[String], conf: DBConfig): Option[String] = {

        val meta = con.getMetaData
        val resPrimaryKeys = meta.getPrimaryKeys(conf.database, null, conf.tableName)
        var primaryKeys = scala.collection.immutable.Set[String]()

        while (resPrimaryKeys.next) {
            val columnName = resPrimaryKeys.getString(4)
            if (setColumns.contains(columnName.toLowerCase)) {
                primaryKeys = primaryKeys + columnName
            } else {
                logger.warn(s"Rejected $columnName")
            }
        }

        resPrimaryKeys.close()
        if (primaryKeys.size > 1) {
            Some(primaryKeys.mkString(","))
        } else if (primaryKeys.size == 1)  {
            Some(primaryKeys.toSeq.head)
        } else {
            None
        }
    }

    val mysqlToRedshiftTypeConverter: Map[String, RedshiftType] = {
        val maxVarcharSize = 65535
        Map(
            "BOOLEAN" -> RedshiftType("INT2"),
            "TINYINT" -> RedshiftType("INT2"),
            "SMALLINT" -> RedshiftType("INT2"),
            "INT" -> RedshiftType("INT4"),
            "BIGINT" -> RedshiftType("INT4"),
            "FLOAT" -> RedshiftType("INT4"),
            "DOUBLE" -> RedshiftType("INT4"),
            "DECIMAL" -> RedshiftType("INT8"),
            "STRING" -> RedshiftType("VARCHAR(65535)"),
            "VARCHAR" -> RedshiftType("VARCHAR",hasPrecision = true, hasScale = false, 4), 
            "BINARY" -> RedshiftType("FLOAT4"),
            "TIMESTAMP" -> RedshiftType("FLOAT8"),
            "DATE" -> RedshiftType("FLOAT8"),
            "DATETIME" -> RedshiftType("VARCHAR", hasPrecision = true, hasScale = false, 4),
            "ARRAY" -> RedshiftType("VARCHAR", hasPrecision = true, hasScale = false, 4),
            "MAP" -> RedshiftType("VARCHAR(1024)"),
            "STRUCT" -> RedshiftType(s"VARCHAR($maxVarcharSize)")
        )
    }

    def transferDateFunction(sql:String): String = {
        //todo:
        ""
    }

    def transferCharFunction(sql:String): String = {
        //todo:
        ""
    }

    /**
      * Alter table to add or delete columns in redshift table if any changes occurs in sql table
      *
      * @param tableDetails sql table details
      * @param redshiftConf redshift configuration
      * @return Query of add and delete columns from redshift table
      */
//    private def alterTableQuery(tableDetails: TableDetails, redshiftConf: DBConfiguration, customFields:Seq[String]): String = {
//
//        val redshiftTableName: String = RedshiftUtil.getTableNameWithSchema(redshiftConf)
//        try {
//            val mainTableColumnNames: Set[String] = RedshiftUtil.getColumnNamesAndTypes(redshiftConf).keys.toSet
//
//            // All columns name must be distinct other wise redshift load will fail
//            val stagingTableColumnAndTypes: Map[String, String] = tableDetails
//                    .validFields
//                    .map { td => td.fieldName.toLowerCase -> td.fieldType }
//                    .toMap
//
//            val stagingTableColumnNames: Set[String] = (stagingTableColumnAndTypes.keys ++ customFields).toSet
//            val addedColumns: Set[String] = stagingTableColumnNames -- mainTableColumnNames
//            val deletedColumns: Set[String] = mainTableColumnNames -- stagingTableColumnNames
//
//            val addColumnsQuery = addedColumns.foldLeft("\n") { (query, columnName) =>
//                query + s"""ALTER TABLE $redshiftTableName ADD COLUMN "$columnName" """ +
//                        stagingTableColumnAndTypes.getOrElse(columnName, "") + ";\n"
//            }
//
//            val deleteColumnQuery = deletedColumns.foldLeft("\n") { (query, columnName) =>
//                query + s"""ALTER TABLE $redshiftTableName DROP COLUMN "$columnName" ;\n"""
//            }
//
//            addColumnsQuery + deleteColumnQuery
//        } catch {
//            case e: Exception =>
//                logger.warn("Error occurred while altering table: \n{}", e.getStackTrace.mkString("\n"))
//                ""
//        }
//    }
}
