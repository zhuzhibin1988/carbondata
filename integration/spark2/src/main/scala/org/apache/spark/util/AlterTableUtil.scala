/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.util

import java.util

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import org.apache.spark.SparkConf
import org.apache.spark.sql.{CarbonEnv, CarbonSession, SparkSession}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.hive.{CarbonRelation, CarbonSessionCatalog}
import org.apache.spark.sql.hive.HiveExternalCatalog._

import org.apache.carbondata.common.exceptions.sql.MalformedCarbonCommandException
import org.apache.carbondata.common.logging.LogServiceFactory
import org.apache.carbondata.core.cache.dictionary.ManageDictionaryAndBTree
import org.apache.carbondata.core.constants.CarbonCommonConstants
import org.apache.carbondata.core.datastore.impl.FileFactory
import org.apache.carbondata.core.locks.{CarbonLockUtil, ICarbonLock, LockUsage}
import org.apache.carbondata.core.metadata.{AbsoluteTableIdentifier, CarbonTableIdentifier}
import org.apache.carbondata.core.metadata.converter.ThriftWrapperSchemaConverterImpl
import org.apache.carbondata.core.metadata.schema.table.CarbonTable
import org.apache.carbondata.core.metadata.schema.table.column.{CarbonDimension, ColumnSchema}
import org.apache.carbondata.core.util.CarbonUtil
import org.apache.carbondata.core.util.path.CarbonTablePath
import org.apache.carbondata.format.{SchemaEvolutionEntry, TableInfo}
import org.apache.carbondata.spark.util.CommonUtil

object AlterTableUtil {

  private val LOGGER = LogServiceFactory.getLogService(this.getClass.getCanonicalName)

  /**
   * Validates that the table exists and acquires meta lock on it.
   *
   * @param dbName
   * @param tableName
   * @param sparkSession
   * @return
   */
  def validateTableAndAcquireLock(dbName: String,
      tableName: String,
      locksToBeAcquired: List[String])
    (sparkSession: SparkSession): List[ICarbonLock] = {
    val relation =
      CarbonEnv.getInstance(sparkSession).carbonMetastore
        .lookupRelation(Option(dbName), tableName)(sparkSession)
        .asInstanceOf[CarbonRelation]
    if (relation == null) {
      LOGGER.audit(s"Alter table request has failed. " +
                   s"Table $dbName.$tableName does not exist")
      sys.error(s"Table $dbName.$tableName does not exist")
    }
    // acquire the lock first
    val table = relation.carbonTable
    val acquiredLocks = ListBuffer[ICarbonLock]()
    try {
      locksToBeAcquired.foreach { lock =>
        acquiredLocks += CarbonLockUtil.getLockObject(table.getAbsoluteTableIdentifier, lock)
      }
      acquiredLocks.toList
    } catch {
      case e: Exception =>
        releaseLocks(acquiredLocks.toList)
        throw e
    }
  }

  /**
   * This method will release the locks acquired for an operation
   *
   * @param locks
   */
  def releaseLocks(locks: List[ICarbonLock]): Unit = {
    locks.foreach { carbonLock =>
      if (carbonLock.unlock()) {
        LOGGER.info("Alter table lock released successfully")
      } else {
        LOGGER.error("Unable to release lock during alter table operation")
      }
    }
  }

  /**
   * This method will release the locks by manually forming a lock path. Specific usage for
   * rename table
   *
   * @param locks
   * @param locksAcquired
   * @param dbName
   * @param tableName
   * @param tablePath
   */
  def releaseLocksManually(locks: List[ICarbonLock],
      locksAcquired: List[String],
      dbName: String,
      tableName: String,
      tablePath: String): Unit = {
    val lockLocation = tablePath
    locks.zip(locksAcquired).foreach { case (carbonLock, lockType) =>
      val lockFilePath = CarbonTablePath.getLockFilePath(lockLocation, lockType)
      if (carbonLock.releaseLockManually(lockFilePath)) {
        LOGGER.info(s"Alter table lock released successfully: ${ lockType }")
      } else {
        LOGGER.error("Unable to release lock during alter table operation")
      }
    }
  }

  /**
   * @param carbonTable
   * @param schemaEvolutionEntry
   * @param thriftTable
   * @param sparkSession
   */
  def updateSchemaInfo(carbonTable: CarbonTable,
      schemaEvolutionEntry: SchemaEvolutionEntry,
      thriftTable: TableInfo,
      cols: Option[Seq[org.apache.carbondata.core.metadata.schema.table.column.ColumnSchema]] =
      None)
    (sparkSession: SparkSession):
    (TableIdentifier,
      String,
      Option[Seq[org.apache.carbondata.core.metadata.schema.table.column.ColumnSchema]]) = {
    val dbName = carbonTable.getDatabaseName
    val tableName = carbonTable.getTableName
    CarbonEnv.getInstance(sparkSession).carbonMetastore
      .updateTableSchemaForAlter(carbonTable.getCarbonTableIdentifier,
        carbonTable.getCarbonTableIdentifier,
        thriftTable,
        schemaEvolutionEntry,
        carbonTable.getAbsoluteTableIdentifier.getTablePath)(sparkSession)
    val tableIdentifier = TableIdentifier(tableName, Some(dbName))
    sparkSession.catalog.refreshTable(tableIdentifier.quotedString)
    val schema = CarbonEnv.getInstance(sparkSession).carbonMetastore
      .lookupRelation(tableIdentifier)(sparkSession).schema.json
    val schemaParts = prepareSchemaJsonForAlterTable(sparkSession.sparkContext.getConf, schema)
    (tableIdentifier, schemaParts, cols)
  }

  /**
   * This method will split schema string into multiple parts of configured size and
   * registers the parts as keys in tableProperties which will be read by spark to prepare
   * Carbon Table fields
   *
   * @param sparkConf
   * @param schemaJsonString
   * @return
   */
  private def prepareSchemaJsonForAlterTable(sparkConf: SparkConf,
      schemaJsonString: String): String = {
    val threshold = sparkConf
      .getInt(CarbonCommonConstants.SPARK_SCHEMA_STRING_LENGTH_THRESHOLD,
        CarbonCommonConstants.SPARK_SCHEMA_STRING_LENGTH_THRESHOLD_DEFAULT)
    // Split the JSON string.
    val parts = schemaJsonString.grouped(threshold).toSeq
    var schemaParts: Seq[String] = Seq.empty
    schemaParts = schemaParts :+ s"'$DATASOURCE_SCHEMA_NUMPARTS'='${ parts.size }'"
    parts.zipWithIndex.foreach { case (part, index) =>
      schemaParts = schemaParts :+ s"'$DATASOURCE_SCHEMA_PART_PREFIX$index'='$part'"
    }
    schemaParts.mkString(",")
  }

  /**
   * This method reverts the changes to the schema if the rename table command fails.
   */
  def revertRenameTableChanges(
      newTableName: String,
      oldCarbonTable: CarbonTable,
      timeStamp: Long)
    (sparkSession: SparkSession): Unit = {
    val tablePath = oldCarbonTable.getTablePath
    val tableId = oldCarbonTable.getCarbonTableIdentifier.getTableId
    val oldCarbonTableIdentifier = oldCarbonTable.getCarbonTableIdentifier
    val database = oldCarbonTable.getDatabaseName
    val newCarbonTableIdentifier = new CarbonTableIdentifier(database, newTableName, tableId)
    val newTablePath = CarbonTablePath.getNewTablePath(tablePath, newTableName)
    val metastore = CarbonEnv.getInstance(sparkSession).carbonMetastore
    val fileType = FileFactory.getFileType(tablePath)
    if (FileFactory.isFileExist(tablePath, fileType)) {
      val tableInfo = metastore.getThriftTableInfo(oldCarbonTable)
      val evolutionEntryList = tableInfo.fact_table.schema_evolution.schema_evolution_history
      val updatedTime = evolutionEntryList.get(evolutionEntryList.size() - 1).time_stamp
      if (updatedTime == timeStamp) {
        LOGGER.error(s"Reverting changes for $database.${oldCarbonTable.getTableName}")
        FileFactory.getCarbonFile(tablePath, fileType)
          .renameForce(CarbonTablePath.getNewTablePath(tablePath, oldCarbonTable.getTableName))
        val absoluteTableIdentifier = AbsoluteTableIdentifier.from(
          newTablePath,
          newCarbonTableIdentifier)
        metastore.revertTableSchemaInAlterFailure(oldCarbonTableIdentifier,
          tableInfo, absoluteTableIdentifier)(sparkSession)
        metastore.removeTableFromMetadata(database, newTableName)
      }
    }
  }

  /**
   * This method reverts the changes to the schema if add column command fails.
   *
   * @param dbName
   * @param tableName
   * @param timeStamp
   * @param sparkSession
   */
  def revertAddColumnChanges(dbName: String, tableName: String, timeStamp: Long)
    (sparkSession: SparkSession): Unit = {
    val metastore = CarbonEnv.getInstance(sparkSession).carbonMetastore
    val carbonTable = CarbonEnv.getCarbonTable(Some(dbName), tableName)(sparkSession)
    val thriftTable: TableInfo = metastore.getThriftTableInfo(carbonTable)
    val evolutionEntryList = thriftTable.fact_table.schema_evolution.schema_evolution_history
    val updatedTime = evolutionEntryList.get(evolutionEntryList.size() - 1).time_stamp
    if (updatedTime == timeStamp) {
      LOGGER.info(s"Reverting changes for $dbName.$tableName")
      val addedSchemas = evolutionEntryList.get(evolutionEntryList.size() - 1).added
      thriftTable.fact_table.table_columns.removeAll(addedSchemas)
      metastore
        .revertTableSchemaInAlterFailure(carbonTable.getCarbonTableIdentifier,
          thriftTable, carbonTable.getAbsoluteTableIdentifier)(sparkSession)
    }
  }

  /**
   * This method reverts the schema changes if drop table command fails.
   *
   * @param dbName
   * @param tableName
   * @param timeStamp
   * @param sparkSession
   */
  def revertDropColumnChanges(dbName: String, tableName: String, timeStamp: Long)
    (sparkSession: SparkSession): Unit = {
    val metastore = CarbonEnv.getInstance(sparkSession).carbonMetastore
    val carbonTable = CarbonEnv.getCarbonTable(Some(dbName), tableName)(sparkSession)
    val thriftTable: TableInfo = metastore.getThriftTableInfo(carbonTable)
    val evolutionEntryList = thriftTable.fact_table.schema_evolution.schema_evolution_history
    val updatedTime = evolutionEntryList.get(evolutionEntryList.size() - 1).time_stamp
    if (updatedTime == timeStamp) {
      LOGGER.error(s"Reverting changes for $dbName.$tableName")
      val removedSchemas = evolutionEntryList.get(evolutionEntryList.size() - 1).removed
      thriftTable.fact_table.table_columns.asScala.foreach { columnSchema =>
        removedSchemas.asScala.foreach { removedSchemas =>
          if (columnSchema.invisible && removedSchemas.column_id == columnSchema.column_id) {
            columnSchema.setInvisible(false)
          }
        }
      }
      metastore
        .revertTableSchemaInAlterFailure(carbonTable.getCarbonTableIdentifier,
          thriftTable, carbonTable.getAbsoluteTableIdentifier)(sparkSession)
    }
  }

  /**
   * This method reverts the changes to schema if the data type change command fails.
   *
   * @param dbName
   * @param tableName
   * @param timeStamp
   * @param sparkSession
   */
  def revertDataTypeChanges(dbName: String, tableName: String, timeStamp: Long)
    (sparkSession: SparkSession): Unit = {
    val metastore = CarbonEnv.getInstance(sparkSession).carbonMetastore
    val carbonTable = CarbonEnv.getCarbonTable(Some(dbName), tableName)(sparkSession)
    val thriftTable: TableInfo = metastore.getThriftTableInfo(carbonTable)
    val evolutionEntryList = thriftTable.fact_table.schema_evolution.schema_evolution_history
    val updatedTime = evolutionEntryList.get(evolutionEntryList.size() - 1).time_stamp
    if (updatedTime == timeStamp) {
      LOGGER.error(s"Reverting changes for $dbName.$tableName")
      val removedColumns = evolutionEntryList.get(evolutionEntryList.size() - 1).removed
      thriftTable.fact_table.table_columns.asScala.foreach { columnSchema =>
        removedColumns.asScala.foreach { removedColumn =>
          if (columnSchema.column_id.equalsIgnoreCase(removedColumn.column_id) &&
              !columnSchema.isInvisible) {
            columnSchema.setData_type(removedColumn.data_type)
            columnSchema.setPrecision(removedColumn.precision)
            columnSchema.setScale(removedColumn.scale)
          }
        }
      }
      metastore
        .revertTableSchemaInAlterFailure(carbonTable.getCarbonTableIdentifier,
          thriftTable, carbonTable.getAbsoluteTableIdentifier)(sparkSession)
    }
  }

  /**
   * This method add/modify the table comments.
   *
   * @param tableIdentifier
   * @param properties
   * @param propKeys
   * @param set
   * @param sparkSession
   */
  def modifyTableProperties(tableIdentifier: TableIdentifier, properties: Map[String, String],
      propKeys: Seq[String], set: Boolean)
    (sparkSession: SparkSession, catalog: CarbonSessionCatalog): Unit = {
    val tableName = tableIdentifier.table
    val dbName = tableIdentifier.database.getOrElse(sparkSession.catalog.currentDatabase)
    LOGGER.audit(s"Alter table newProperties request has been received for $dbName.$tableName")
    val locksToBeAcquired = List(LockUsage.METADATA_LOCK, LockUsage.COMPACTION_LOCK)
    var locks = List.empty[ICarbonLock]
    val timeStamp = 0L
    try {
      locks = AlterTableUtil
        .validateTableAndAcquireLock(dbName, tableName, locksToBeAcquired)(sparkSession)
      val metastore = CarbonEnv.getInstance(sparkSession).carbonMetastore
      val carbonTable = CarbonEnv.getCarbonTable(Some(dbName), tableName)(sparkSession)
      val lowerCasePropertiesMap: mutable.Map[String, String] = mutable.Map.empty
      // convert all the keys to lower case
      properties.foreach { entry =>
        lowerCasePropertiesMap.put(entry._1.toLowerCase, entry._2)
      }
      // validate the required cache level properties
      validateColumnMetaCacheAndCacheLevel(carbonTable, lowerCasePropertiesMap)
      // get the latest carbon table
      // read the latest schema file
      val thriftTableInfo: TableInfo = metastore.getThriftTableInfo(carbonTable)
      val schemaConverter = new ThriftWrapperSchemaConverterImpl()
      val wrapperTableInfo = schemaConverter.fromExternalToWrapperTableInfo(
        thriftTableInfo,
        dbName,
        tableName,
        carbonTable.getTablePath)
      val schemaEvolutionEntry = new org.apache.carbondata.core.metadata.schema.SchemaEvolutionEntry
      schemaEvolutionEntry.setTimeStamp(timeStamp)
      val thriftTable = schemaConverter.fromWrapperToExternalTableInfo(
        wrapperTableInfo, dbName, tableName)
      val tblPropertiesMap: mutable.Map[String, String] =
        thriftTable.fact_table.getTableProperties.asScala
      // below map will be used for cache invalidation. As tblProperties map is getting modified
      // in the next few steps the original map need to be retained for any decision making
      val existingTablePropertiesMap = mutable.Map(tblPropertiesMap.toSeq: _*)
      if (set) {
        // This overrides old newProperties and update the comment parameter of thriftTable
        // with the newly added/modified comment since thriftTable also holds comment as its
        // direct property.
        lowerCasePropertiesMap.foreach { property => if (validateTableProperties(property._1)) {
          tblPropertiesMap.put(property._1, property._2)
        } else {
          val errorMessage = "Error: Invalid option(s): " + property._1.toString()
          throw new MalformedCarbonCommandException(errorMessage)
        }}
      } else {
        // This removes the comment parameter from thriftTable
        // since thriftTable also holds comment as its property.
        propKeys.foreach { propKey =>
          if (validateTableProperties(propKey)) {
            tblPropertiesMap.remove(propKey.toLowerCase)
          } else {
            val errorMessage = "Error: Invalid option(s): " + propKey
            throw new MalformedCarbonCommandException(errorMessage)
          }
        }
      }
      val (tableIdentifier, schemParts, cols) = updateSchemaInfo(carbonTable,
        schemaConverter.fromWrapperToExternalSchemaEvolutionEntry(schemaEvolutionEntry),
        thriftTable)(sparkSession)
      catalog.alterTable(tableIdentifier, schemParts, cols)
      sparkSession.catalog.refreshTable(tableIdentifier.quotedString)
      // check and clear the block/blocklet cache
      checkAndClearBlockletCache(carbonTable,
        existingTablePropertiesMap,
        lowerCasePropertiesMap,
        propKeys,
        set)
      LOGGER.info(s"Alter table newProperties is successful for table $dbName.$tableName")
      LOGGER.audit(s"Alter table newProperties is successful for table $dbName.$tableName")
    } catch {
      case e: Exception =>
        LOGGER.error(e, "Alter table newProperties failed")
        sys.error(s"Alter table newProperties operation failed: ${e.getMessage}")
    } finally {
      // release lock after command execution completion
      AlterTableUtil.releaseLocks(locks)
    }
  }

  private def validateTableProperties(propKey: String): Boolean = {
    val supportedOptions = Seq("STREAMING", "COMMENT", "COLUMN_META_CACHE", "CACHE_LEVEL")
    supportedOptions.contains(propKey.toUpperCase)
  }

  /**
   * validate column meta cache and cache level properties if configured by the user
   *
   * @param carbonTable
   * @param propertiesMap
   */
  private def validateColumnMetaCacheAndCacheLevel(carbonTable: CarbonTable,
      propertiesMap: mutable.Map[String, String]): Unit = {
    // validate column meta cache property
    if (propertiesMap.get(CarbonCommonConstants.COLUMN_META_CACHE).isDefined) {
      // Column meta cache is not allowed for child tables and dataMaps
      if (carbonTable.isChildDataMap) {
        throw new MalformedCarbonCommandException(s"Table property ${
          CarbonCommonConstants.COLUMN_META_CACHE} is not allowed for child datamaps")
      }
      val schemaList: util.List[ColumnSchema] = CarbonUtil
        .getColumnSchemaList(carbonTable.getDimensionByTableName(carbonTable.getTableName),
          carbonTable.getMeasureByTableName(carbonTable.getTableName))
      val tableColumns: Seq[String] = schemaList.asScala
        .map(columnSchema => columnSchema.getColumnName)
      CommonUtil
        .validateColumnMetaCacheFields(carbonTable.getDatabaseName,
          carbonTable.getTableName,
          tableColumns,
          propertiesMap.get(CarbonCommonConstants.COLUMN_META_CACHE).get,
          propertiesMap)
      val columnsToBeCached = propertiesMap.get(CarbonCommonConstants.COLUMN_META_CACHE).get
      validateForComplexTypeColumn(carbonTable, columnsToBeCached)
    }
    // validate cache level property
    if (propertiesMap.get(CarbonCommonConstants.CACHE_LEVEL).isDefined) {
      // Cache level is not allowed for child tables and dataMaps
      if (carbonTable.isChildDataMap) {
        throw new MalformedCarbonCommandException(s"Table property ${
          CarbonCommonConstants.CACHE_LEVEL} is not allowed for child datamaps")
      }
      CommonUtil.validateCacheLevel(
        propertiesMap.get(CarbonCommonConstants.CACHE_LEVEL).get,
        propertiesMap)
    }
  }

  /**
   * This method will validate if there is any complex type column in the columns to be cached
   *
   * @param carbonTable
   * @param cachedColumns
   */
  private def validateForComplexTypeColumn(carbonTable: CarbonTable,
      cachedColumns: String): Unit = {
    if (cachedColumns.nonEmpty) {
      cachedColumns.split(",").foreach { column =>
        val dimension = carbonTable.getDimensionByName(carbonTable.getTableName, column)
        if (null != dimension && dimension.isComplex) {
          val errorMessage =
            s"$column is a complex type column and complex type is not allowed for " +
            s"the option(s): ${ CarbonCommonConstants.COLUMN_META_CACHE }"
          throw new MalformedCarbonCommandException(errorMessage)
        }
      }
    }
  }

  /**
   * This method will check and clear the driver block/blocklet cache
   *
   * @param carbonTable
   * @param existingTableProperties
   * @param newProperties
   * @param propKeys
   * @param set
   */
  private def checkAndClearBlockletCache(carbonTable: CarbonTable,
      existingTableProperties: scala.collection.mutable.Map[String, String],
      newProperties: mutable.Map[String, String],
      propKeys: Seq[String],
      set: Boolean): Unit = {
    if (set) {
      clearBlockletCacheForCachingProperties(carbonTable, existingTableProperties, newProperties)
    } else {
      // convert all the unset keys to lower case
      val propertiesToBeRemoved = propKeys.map(key => key.toLowerCase)
      // This is unset scenario and the cache needs to be cleaned only when
      // 1. column_meta_cache property is getting unset and existing table properties contains
      // this property
      // 2. cache_level property is being unset and existing table properties contains this property
      // and the existing value is not equal to default value because after un-setting the property
      // the cache should be loaded again with default value
      if (propertiesToBeRemoved.contains(CarbonCommonConstants.COLUMN_META_CACHE) &&
          existingTableProperties.get(CarbonCommonConstants.COLUMN_META_CACHE).isDefined) {
        ManageDictionaryAndBTree.invalidateBTreeCache(carbonTable)
      } else if (propertiesToBeRemoved.contains(CarbonCommonConstants.CACHE_LEVEL)) {
        val cacheLevel = existingTableProperties.get(CarbonCommonConstants.CACHE_LEVEL)
        if (cacheLevel.isDefined &&
            !cacheLevel.equals(CarbonCommonConstants.CACHE_LEVEL_DEFAULT_VALUE)) {
          ManageDictionaryAndBTree.invalidateBTreeCache(carbonTable)
        }
      }
    }
  }

  /**
   * This method will validate the column_meta_cache and cache_level properties and clear the
   * driver block/blocklet cache
   *
   * @param carbonTable
   * @param tblPropertiesMap
   * @param newProperties
   */
  private def clearBlockletCacheForCachingProperties(
      carbonTable: CarbonTable,
      tblPropertiesMap: scala.collection.mutable.Map[String, String],
      newProperties: mutable.Map[String, String]): Unit = {
    // check if column meta cache is defined. if defined then validate and clear the BTree cache
    // if required
    val columnMetaCacheProperty = newProperties.get(CarbonCommonConstants.COLUMN_META_CACHE)
    columnMetaCacheProperty match {
      case Some(newColumnsToBeCached) =>
        if (!checkIfColumnsAreAlreadyCached(carbonTable, tblPropertiesMap
          .get(CarbonCommonConstants.COLUMN_META_CACHE), newColumnsToBeCached)) {
          ManageDictionaryAndBTree.invalidateBTreeCache(carbonTable)
        }
      case None =>
      // don't do anything
    }
    // check if column meta cache is defined. if defined then validate and clear the BTree cache
    // if required
    val cacheLevelProperty = newProperties.get(CarbonCommonConstants.CACHE_LEVEL)
    cacheLevelProperty match {
      case Some(newCacheLevelValue) =>
        if (!isCacheLevelValid(tblPropertiesMap.get(CarbonCommonConstants.CACHE_LEVEL),
          newCacheLevelValue)) {
          ManageDictionaryAndBTree.invalidateBTreeCache(carbonTable)
        }
      case None =>
      // don't do anything
    }
  }

  /**
   * Method to verify if the existing cache level is same as the new cache level
   *
   * @param existingCacheLevelValue
   * @param newCacheLevelValue
   * @return
   */
  private def isCacheLevelValid(existingCacheLevelValue: Option[String],
      newCacheLevelValue: String): Boolean = {
    existingCacheLevelValue match {
      case Some(existingValue) =>
        existingValue.equals(newCacheLevelValue)
      case None =>
        false
    }
  }

  /**
   * Check the new columns to be cached with the already cached columns. If count of new columns
   * and already cached columns is same and all the new columns are already cached then
   * false will be returned else true
   *
   * @param carbonTable
   * @param existingCacheColumns
   * @param newColumnsToBeCached
   * @return
   */
  private def checkIfColumnsAreAlreadyCached(
      carbonTable: CarbonTable,
      existingCacheColumns: Option[String],
      newColumnsToBeCached: String): Boolean = {
    val newColumns = newColumnsToBeCached.split(",").map(x => x.trim.toLowerCase)
    val isCached = existingCacheColumns match {
      case Some(value) =>
        val existingProperty = value.split(",").map(x => x.trim.toLowerCase)
        compareColumns(existingProperty, newColumns)
      case None =>
        // By default all the columns in the table will be cached. This case is to compare all the
        // table columns already cached to the newly specified cached columns
        val schemaList: util.List[ColumnSchema] = CarbonUtil
          .getColumnSchemaList(carbonTable.getDimensionByTableName(carbonTable.getTableName),
            carbonTable.getMeasureByTableName(carbonTable.getTableName))
        val tableColumns: Array[String] = schemaList.asScala
          .map(columnSchema => columnSchema.getColumnName).toArray
        compareColumns(tableColumns, newColumns)
    }
    isCached
  }

  /**
   * compare the existing cache columns and the new columns to be cached
   *
   * @param existingCachedColumns
   * @param newColumnsToBeCached
   * @return
   */
  private def compareColumns(existingCachedColumns: Array[String],
      newColumnsToBeCached: Array[String]): Boolean = {
    val allColumnsMatch = if (existingCachedColumns.length == newColumnsToBeCached.length) {
      existingCachedColumns.filter(col => !newColumnsToBeCached.contains(col)).length == 0
    } else {
      false
    }
    allColumnsMatch
  }

}
