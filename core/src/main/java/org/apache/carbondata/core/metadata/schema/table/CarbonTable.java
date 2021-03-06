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

package org.apache.carbondata.core.metadata.schema.table;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.carbondata.common.exceptions.sql.MalformedDataMapCommandException;
import org.apache.carbondata.common.logging.LogService;
import org.apache.carbondata.common.logging.LogServiceFactory;
import org.apache.carbondata.core.constants.CarbonCommonConstants;
import org.apache.carbondata.core.datamap.DataMapStoreManager;
import org.apache.carbondata.core.datamap.TableDataMap;
import org.apache.carbondata.core.datamap.dev.DataMapFactory;
import org.apache.carbondata.core.features.TableOperation;
import org.apache.carbondata.core.metadata.AbsoluteTableIdentifier;
import org.apache.carbondata.core.metadata.CarbonTableIdentifier;
import org.apache.carbondata.core.metadata.encoder.Encoding;
import org.apache.carbondata.core.metadata.schema.BucketingInfo;
import org.apache.carbondata.core.metadata.schema.PartitionInfo;
import org.apache.carbondata.core.metadata.schema.SchemaReader;
import org.apache.carbondata.core.metadata.schema.partition.PartitionType;
import org.apache.carbondata.core.metadata.schema.table.column.CarbonColumn;
import org.apache.carbondata.core.metadata.schema.table.column.CarbonDimension;
import org.apache.carbondata.core.metadata.schema.table.column.CarbonImplicitDimension;
import org.apache.carbondata.core.metadata.schema.table.column.CarbonMeasure;
import org.apache.carbondata.core.metadata.schema.table.column.ColumnSchema;
import org.apache.carbondata.core.scan.expression.Expression;
import org.apache.carbondata.core.scan.filter.FilterExpressionProcessor;
import org.apache.carbondata.core.scan.filter.intf.FilterOptimizer;
import org.apache.carbondata.core.scan.filter.optimizer.RangeFilterOptmizer;
import org.apache.carbondata.core.scan.filter.resolver.FilterResolverIntf;
import org.apache.carbondata.core.scan.model.QueryModel;
import org.apache.carbondata.core.util.CarbonUtil;
import org.apache.carbondata.core.util.DataTypeUtil;
import org.apache.carbondata.core.util.path.CarbonTablePath;

import static org.apache.carbondata.core.util.CarbonUtil.thriftColumnSchemaToWrapperColumnSchema;

/**
 * Mapping class for Carbon actual table
 */
public class CarbonTable implements Serializable {

  private static final LogService LOGGER =
      LogServiceFactory.getLogService(CarbonTable.class.getName());

  /**
   * the cached table info
   */
  private TableInfo tableInfo;

  /**
   * serialization id
   */
  private static final long serialVersionUID = 8696507171227156445L;

  /**
   * TableName, Dimensions list. This map will contain allDimensions which are visible
   */
  private Map<String, List<CarbonDimension>> tableDimensionsMap;

  /**
   * list of all the allDimensions
   */
  private List<CarbonDimension> allDimensions;

  private Map<String, List<CarbonColumn>> createOrderColumn;

  /**
   * TableName, Dimensions and children allDimensions list
   */
  private Map<String, List<CarbonDimension>> tablePrimitiveDimensionsMap;

  /**
   * table allMeasures list.
   */
  private Map<String, List<CarbonDimension>> tableImplicitDimensionsMap;

  /**
   * table allMeasures list. This map will contain allDimensions which are visible
   */
  private Map<String, List<CarbonMeasure>> tableMeasuresMap;

  /**
   * list of allMeasures
   */
  private List<CarbonMeasure> allMeasures;

  /**
   * table bucket map.
   */
  private Map<String, BucketingInfo> tableBucketMap;

  /**
   * table partition info
   */
  private Map<String, PartitionInfo> tablePartitionMap;

  /**
   * tableUniqueName
   */
  private String tableUniqueName;

  /**
   * last updated time
   */
  private long tableLastUpdatedTime;

  /**
   * table block size in MB
   */
  private int blockSize;

  /**
   * the number of columns in SORT_COLUMNS
   */
  private int numberOfSortColumns;

  /**
   * the number of no dictionary columns in SORT_COLUMNS
   */
  private int numberOfNoDictSortColumns;

  private int dimensionOrdinalMax;

  private boolean hasDataMapSchema;

  /**
   * is local dictionary generation enabled for the table
   */
  private boolean isLocalDictionaryEnabled;

  /**
   * local dictionary generation threshold
   */
  private int localDictionaryThreshold;

  /**
   * The boolean field which points if the data written for Non Transactional Table
   * or Transactional Table.
   * transactional table means carbon will provide transactional support when user doing data
   * management like data loading, whether it is success or failure, data will be in consistent
   * state
   * The difference between Transactional and non Transactional table is
   * non Transactional Table will not contain any Metadata folder and subsequently
   * no TableStatus or Schema files.
   */
  private boolean isTransactionalTable = true;

  private CarbonTable() {
    this.tableDimensionsMap = new HashMap<String, List<CarbonDimension>>();
    this.tableImplicitDimensionsMap = new HashMap<String, List<CarbonDimension>>();
    this.tableMeasuresMap = new HashMap<String, List<CarbonMeasure>>();
    this.tableBucketMap = new HashMap<>();
    this.tablePartitionMap = new HashMap<>();
    this.createOrderColumn = new HashMap<String, List<CarbonColumn>>();
    this.tablePrimitiveDimensionsMap = new HashMap<String, List<CarbonDimension>>();
  }

  /**
   * During creation of TableInfo from hivemetastore the DataMapSchemas and the columns
   * DataTypes are not converted to the appropriate child classes.
   * This method will cast the same to the appropriate classes
   */
  public static void updateTableInfo(TableInfo tableInfo) {
    List<DataMapSchema> dataMapSchemas = new ArrayList<>();
    for (DataMapSchema dataMapSchema : tableInfo.getDataMapSchemaList()) {
      DataMapSchema newDataMapSchema = DataMapSchemaFactory.INSTANCE
          .getDataMapSchema(dataMapSchema.getDataMapName(), dataMapSchema.getProviderName());
      newDataMapSchema.setChildSchema(dataMapSchema.getChildSchema());
      newDataMapSchema.setProperties(dataMapSchema.getProperties());
      newDataMapSchema.setRelationIdentifier(dataMapSchema.getRelationIdentifier());
      dataMapSchemas.add(newDataMapSchema);
    }
    tableInfo.setDataMapSchemaList(dataMapSchemas);
    for (ColumnSchema columnSchema : tableInfo.getFactTable().getListOfColumns()) {
      columnSchema.setDataType(
          DataTypeUtil.valueOf(
              columnSchema.getDataType(), columnSchema.getPrecision(), columnSchema.getScale()));
    }
    List<DataMapSchema> childSchema = tableInfo.getDataMapSchemaList();
    for (DataMapSchema dataMapSchema : childSchema) {
      if (dataMapSchema.childSchema != null
          && dataMapSchema.childSchema.getListOfColumns().size() > 0) {
        for (ColumnSchema columnSchema : dataMapSchema.childSchema.getListOfColumns()) {
          columnSchema.setDataType(DataTypeUtil
              .valueOf(columnSchema.getDataType(), columnSchema.getPrecision(),
                  columnSchema.getScale()));
        }
      }
    }
    if (tableInfo.getFactTable().getBucketingInfo() != null) {
      for (ColumnSchema columnSchema :
          tableInfo.getFactTable().getBucketingInfo().getListOfColumns()) {
        columnSchema.setDataType(
            DataTypeUtil.valueOf(
                columnSchema.getDataType(), columnSchema.getPrecision(), columnSchema.getScale()));
      }
    }
    if (tableInfo.getFactTable().getPartitionInfo() != null) {
      for (ColumnSchema columnSchema : tableInfo.getFactTable().getPartitionInfo()
          .getColumnSchemaList()) {
        columnSchema.setDataType(DataTypeUtil
            .valueOf(columnSchema.getDataType(), columnSchema.getPrecision(),
                columnSchema.getScale()));
      }
    }
  }

  public static CarbonTable buildTable(
      String tablePath,
      String tableName) throws IOException {
    TableInfo tableInfoInfer = CarbonUtil.buildDummyTableInfo(tablePath, "null", "null");
    File[] dataFiles = new File(tablePath).listFiles(new FilenameFilter() {
      @Override
      public boolean accept(File dir, String name) {
        if (name == null) {
          return false;
        }
        return name.endsWith("carbonindex");
      }
    });
    if (dataFiles == null || dataFiles.length < 1) {
      throw new RuntimeException("Carbon index file not exists.");
    }
    org.apache.carbondata.format.TableInfo tableInfo = CarbonUtil
        .inferSchemaFromIndexFile(dataFiles[0].toString(), tableName);
    List<ColumnSchema> columnSchemaList = new ArrayList<ColumnSchema>();
    for (org.apache.carbondata.format.ColumnSchema thriftColumnSchema : tableInfo
        .getFact_table().getTable_columns()) {
      ColumnSchema columnSchema = thriftColumnSchemaToWrapperColumnSchema(thriftColumnSchema);
      if (columnSchema.getColumnReferenceId() == null) {
        columnSchema.setColumnReferenceId(columnSchema.getColumnUniqueId());
      }
      columnSchemaList.add(columnSchema);
    }
    tableInfoInfer.getFactTable().setListOfColumns(columnSchemaList);
    return CarbonTable.buildFromTableInfo(tableInfoInfer);
  }

  public static CarbonTable buildDummyTable(String tablePath) throws IOException {
    TableInfo tableInfoInfer = CarbonUtil.buildDummyTableInfo(tablePath, "null", "null");
    return CarbonTable.buildFromTableInfo(tableInfoInfer);
  }

  public static CarbonTable buildFromTablePath(String tableName, String dbName, String tablePath)
      throws IOException {
    return SchemaReader
        .readCarbonTableFromStore(AbsoluteTableIdentifier.from(tablePath, dbName, tableName));
  }
  /**
   * @param tableInfo
   */
  public static CarbonTable buildFromTableInfo(TableInfo tableInfo) {
    CarbonTable table = new CarbonTable();
    updateTableByTableInfo(table, tableInfo);
    return table;
  }

  /**
   * fill columns as per user provided order
   *
   * @param tableName
   */
  private void fillCreateOrderColumn(String tableName) {
    List<CarbonColumn> columns = new ArrayList<CarbonColumn>();
    List<CarbonDimension> dimensions = this.tableDimensionsMap.get(tableName);
    List<CarbonMeasure> measures = this.tableMeasuresMap.get(tableName);
    columns.addAll(dimensions);
    columns.addAll(measures);
    Collections.sort(columns, new Comparator<CarbonColumn>() {

      @Override public int compare(CarbonColumn o1, CarbonColumn o2) {
        return Integer.compare(o1.getSchemaOrdinal(), o2.getSchemaOrdinal());
      }

    });
    this.createOrderColumn.put(tableName, columns);
  }

  /**
   * Fill allDimensions and allMeasures for carbon table
   *
   * @param tableSchema
   */
  private void fillDimensionsAndMeasuresForTables(TableSchema tableSchema) {
    List<CarbonDimension> primitiveDimensions = new ArrayList<CarbonDimension>();
    List<CarbonDimension> implicitDimensions = new ArrayList<CarbonDimension>();
    allDimensions = new ArrayList<CarbonDimension>();
    allMeasures = new ArrayList<CarbonMeasure>();
    this.tablePrimitiveDimensionsMap.put(this.tableUniqueName, primitiveDimensions);
    this.tableImplicitDimensionsMap.put(tableSchema.getTableName(), implicitDimensions);
    int dimensionOrdinal = 0;
    int measureOrdinal = 0;
    int keyOrdinal = 0;
    int columnGroupOrdinal = -1;
    int previousColumnGroupId = -1;
    List<ColumnSchema> listOfColumns = tableSchema.getListOfColumns();
    int complexTypeOrdinal = -1;
    for (int i = 0; i < listOfColumns.size(); i++) {
      ColumnSchema columnSchema = listOfColumns.get(i);
      if (columnSchema.isDimensionColumn()) {
        if (columnSchema.getNumberOfChild() > 0) {
          CarbonDimension complexDimension =
              new CarbonDimension(columnSchema, dimensionOrdinal++, columnSchema.getSchemaOrdinal(),
                  -1, -1, ++complexTypeOrdinal);
          complexDimension.initializeChildDimensionsList(columnSchema.getNumberOfChild());
          allDimensions.add(complexDimension);
          dimensionOrdinal =
              readAllComplexTypeChildrens(dimensionOrdinal, columnSchema.getNumberOfChild(),
                  listOfColumns, complexDimension, primitiveDimensions);
          i = dimensionOrdinal - 1;
          complexTypeOrdinal = assignComplexOrdinal(complexDimension, complexTypeOrdinal);
        } else {
          if (!columnSchema.isInvisible() && columnSchema.isSortColumn()) {
            this.numberOfSortColumns++;
          }
          if (!columnSchema.getEncodingList().contains(Encoding.DICTIONARY)) {
            CarbonDimension dimension = new CarbonDimension(
                columnSchema, dimensionOrdinal++, columnSchema.getSchemaOrdinal(), -1, -1, -1);
            if (!columnSchema.isInvisible() && columnSchema.isSortColumn()) {
              this.numberOfNoDictSortColumns++;
            }
            allDimensions.add(dimension);
            primitiveDimensions.add(dimension);
          } else if (columnSchema.getEncodingList().contains(Encoding.DICTIONARY)
              && columnSchema.getColumnGroupId() == -1) {
            CarbonDimension dimension = new CarbonDimension(
                columnSchema, dimensionOrdinal++, columnSchema.getSchemaOrdinal(), keyOrdinal++,
                -1, -1);
            allDimensions.add(dimension);
            primitiveDimensions.add(dimension);
          } else {
            columnGroupOrdinal =
                previousColumnGroupId == columnSchema.getColumnGroupId() ? ++columnGroupOrdinal : 0;
            previousColumnGroupId = columnSchema.getColumnGroupId();
            CarbonDimension dimension = new CarbonDimension(
                columnSchema, dimensionOrdinal++, columnSchema.getSchemaOrdinal(), keyOrdinal++,
                columnGroupOrdinal, -1);
            allDimensions.add(dimension);
            primitiveDimensions.add(dimension);
          }
        }
      } else {
        allMeasures.add(
            new CarbonMeasure(columnSchema, measureOrdinal++, columnSchema.getSchemaOrdinal()));
      }
    }
    fillVisibleDimensions(tableSchema.getTableName());
    fillVisibleMeasures(tableSchema.getTableName());
    addImplicitDimension(dimensionOrdinal, implicitDimensions);

    dimensionOrdinalMax = dimensionOrdinal;
  }

  /**
   * This method will add implict dimension into carbontable
   *
   * @param dimensionOrdinal
   * @param dimensions
   */
  private void addImplicitDimension(int dimensionOrdinal, List<CarbonDimension> dimensions) {
    dimensions.add(new CarbonImplicitDimension(dimensionOrdinal,
        CarbonCommonConstants.CARBON_IMPLICIT_COLUMN_POSITIONID));
    dimensions.add(new CarbonImplicitDimension(dimensionOrdinal + 1,
        CarbonCommonConstants.CARBON_IMPLICIT_COLUMN_TUPLEID));
  }

  /**
   * to get the all dimension of a table
   *
   * @param tableName
   * @return
   */
  public List<CarbonDimension> getImplicitDimensionByTableName(String tableName) {
    return tableImplicitDimensionsMap.get(tableName);
  }

  /**
   * Read all primitive/complex children and set it as list of child carbon dimension to parent
   * dimension
   *
   * @param dimensionOrdinal
   * @param childCount
   * @param listOfColumns
   * @param parentDimension
   * @return
   */
  private int readAllComplexTypeChildrens(int dimensionOrdinal, int childCount,
      List<ColumnSchema> listOfColumns, CarbonDimension parentDimension,
      List<CarbonDimension> primitiveDimensions) {
    for (int i = 0; i < childCount; i++) {
      ColumnSchema columnSchema = listOfColumns.get(dimensionOrdinal);
      if (columnSchema.isDimensionColumn()) {
        if (columnSchema.getNumberOfChild() > 0) {
          CarbonDimension complexDimension =
              new CarbonDimension(columnSchema, dimensionOrdinal++, columnSchema.getSchemaOrdinal(),
                  -1, -1, -1);
          complexDimension.initializeChildDimensionsList(columnSchema.getNumberOfChild());
          parentDimension.getListOfChildDimensions().add(complexDimension);
          dimensionOrdinal =
              readAllComplexTypeChildrens(dimensionOrdinal, columnSchema.getNumberOfChild(),
                  listOfColumns, complexDimension, primitiveDimensions);
        } else {
          CarbonDimension carbonDimension =
              new CarbonDimension(columnSchema, dimensionOrdinal++, columnSchema.getSchemaOrdinal(),
                  -1, -1, -1);
          parentDimension.getListOfChildDimensions().add(carbonDimension);
          primitiveDimensions.add(carbonDimension);
        }
      }
    }
    return dimensionOrdinal;
  }

  /**
   * Read all primitive/complex children and set it as list of child carbon dimension to parent
   * dimension
   */
  private int assignComplexOrdinal(CarbonDimension parentDimension, int complexDimensionOrdianl) {
    for (int i = 0; i < parentDimension.getNumberOfChild(); i++) {
      CarbonDimension dimension = parentDimension.getListOfChildDimensions().get(i);
      if (dimension.getNumberOfChild() > 0) {
        dimension.setComplexTypeOridnal(++complexDimensionOrdianl);
        complexDimensionOrdianl = assignComplexOrdinal(dimension, complexDimensionOrdianl);
      } else {
        parentDimension.getListOfChildDimensions().get(i)
            .setComplexTypeOridnal(++complexDimensionOrdianl);
      }
    }
    return complexDimensionOrdianl;
  }

  /**
   * @return the databaseName
   */
  public String getDatabaseName() {
    return tableInfo.getDatabaseName();
  }

  /**
   * @return the tabelName
   */
  public String getTableName() {
    return tableInfo.getFactTable().getTableName();
  }

  /**
   * @return the tableUniqueName
   */
  public String getTableUniqueName() {
    return tableUniqueName;
  }

  /**
   * is local dictionary enabled for the table
   * @return
   */
  public boolean isLocalDictionaryEnabled() {
    return false;
  }

  /**
   * set whether local dictionary enabled or not
   * @param localDictionaryEnabled
   */
  public void setLocalDictionaryEnabled(boolean localDictionaryEnabled) {
    isLocalDictionaryEnabled = localDictionaryEnabled;
  }

  /**
   * @return local dictionary generation threshold
   */
  public int getLocalDictionaryThreshold() {
    return localDictionaryThreshold;
  }

  /**
   * set the local dictionary generation threshold
   * @param localDictionaryThreshold
   */
  public void setLocalDictionaryThreshold(int localDictionaryThreshold) {
    this.localDictionaryThreshold = localDictionaryThreshold;
  }

  /**
   * build table unique name
   * all should call this method to build table unique name
   * @param databaseName
   * @param tableName
   * @return
   */
  public static String buildUniqueName(String databaseName, String tableName) {
    return databaseName + CarbonCommonConstants.UNDERSCORE + tableName;
  }

  /**
   * Return the metadata path of the table
   */
  public String getMetadataPath() {
    return CarbonTablePath.getMetadataPath(getTablePath());
  }

  /**
   * Return the segment path of the specified segmentId
   */
  public String getSegmentPath(String segmentId) {
    return CarbonTablePath.getSegmentPath(getTablePath(), segmentId);
  }

  /**
   * @return store path
   */
  public String getTablePath() {
    return tableInfo.getOrCreateAbsoluteTableIdentifier().getTablePath();
  }

  /**
   * @return the tableLastUpdatedTime
   */
  public long getTableLastUpdatedTime() {
    return tableLastUpdatedTime;
  }

  /**
   * to get the number of dimension present in the table
   *
   * @param tableName
   * @return number of dimension present the table
   */
  public int getNumberOfDimensions(String tableName) {
    return tableDimensionsMap.get(tableName).size();
  }

  /**
   * to get the number of allMeasures present in the table
   *
   * @param tableName
   * @return number of allMeasures present the table
   */
  public int getNumberOfMeasures(String tableName) {
    return tableMeasuresMap.get(tableName).size();
  }

  /**
   * to get the all dimension of a table
   *
   * @param tableName
   * @return all dimension of a table
   */
  public List<CarbonDimension> getDimensionByTableName(String tableName) {
    return tableDimensionsMap.get(tableName);
  }

  /**
   * to get the all measure of a table
   *
   * @param tableName
   * @return all measure of a table
   */
  public List<CarbonMeasure> getMeasureByTableName(String tableName) {
    return tableMeasuresMap.get(tableName);
  }

  /**
   * Return all dimensions of the table
   */
  public List<CarbonDimension> getDimensions() {
    return tableDimensionsMap.get(getTableName());
  }

  /**
   * Return all measure of the table
   */
  public List<CarbonMeasure> getMeasures() {
    return tableMeasuresMap.get(getTableName());
  }

  /**
   * This will give user created order column
   *
   * @return
   */
  public List<CarbonColumn> getCreateOrderColumn(String tableName) {
    return createOrderColumn.get(tableName);
  }

  /**
   * This method will give storage order column list
   */
  public List<CarbonColumn> getStreamStorageOrderColumn(String tableName) {
    List<CarbonDimension> dimensions = tableDimensionsMap.get(tableName);
    List<CarbonMeasure> measures = tableMeasuresMap.get(tableName);
    List<CarbonColumn> columnList = new ArrayList<>(dimensions.size() + measures.size());
    List<CarbonColumn> complexDimensionList = new ArrayList<>(dimensions.size());
    for (CarbonColumn column : dimensions) {
      if (column.isComplex()) {
        complexDimensionList.add(column);
      } else {
        columnList.add(column);
      }
    }
    columnList.addAll(complexDimensionList);
    for (CarbonColumn column : measures) {
      if (!(column.getColName().equals("default_dummy_measure"))) {
        columnList.add(column);
      }
    }
    return columnList;
  }

  /**
   * to get particular measure from a table
   *
   * @param tableName
   * @param columnName
   * @return
   */
  public CarbonMeasure getMeasureByName(String tableName, String columnName) {
    List<CarbonMeasure> measureList = tableMeasuresMap.get(tableName);
    for (CarbonMeasure measure : measureList) {
      if (measure.getColName().equalsIgnoreCase(columnName)) {
        return measure;
      }
    }
    return null;
  }

  /**
   * to get particular dimension from a table
   *
   * @param tableName
   * @param columnName
   * @return
   */
  public CarbonDimension getDimensionByName(String tableName, String columnName) {
    CarbonDimension carbonDimension = null;
    List<CarbonDimension> dimList = tableDimensionsMap.get(tableName);
    String[] colSplits = columnName.split("\\.");
    StringBuffer tempColName = new StringBuffer(colSplits[0]);
    for (String colSplit : colSplits) {
      if (!tempColName.toString().equalsIgnoreCase(colSplit)) {
        tempColName = tempColName.append(".").append(colSplit);
      }
      carbonDimension = getCarbonDimension(tempColName.toString(), dimList);
      if (carbonDimension != null && carbonDimension.getListOfChildDimensions() != null) {
        dimList = carbonDimension.getListOfChildDimensions();
      }
    }
    List<CarbonDimension> implicitDimList = tableImplicitDimensionsMap.get(tableName);
    if (carbonDimension == null) {
      carbonDimension = getCarbonDimension(columnName, implicitDimList);
    }

    if (colSplits.length > 1) {
      List<CarbonDimension> dimLists = tableDimensionsMap.get(tableName);
      for (CarbonDimension dims : dimLists) {
        if (dims.getColName().equalsIgnoreCase(colSplits[0])) {
          // Set the parent Dimension
          carbonDimension
              .setComplexParentDimension(getDimensionBasedOnOrdinal(dimLists, dims.getOrdinal()));
          break;
        }
      }
    }
    return carbonDimension;
  }

  /**
   * Get Dimension for columnName from list of dimensions
   *
   * @param columnName
   * @param dimensions
   * @return
   */
  public static CarbonDimension getCarbonDimension(String columnName,
      List<CarbonDimension> dimensions) {
    CarbonDimension carbonDimension = null;
    for (CarbonDimension dim : dimensions) {
      if (dim.getColName().equalsIgnoreCase(columnName)) {
        carbonDimension = dim;
        break;
      }
    }
    return carbonDimension;
  }

  private CarbonDimension getDimensionBasedOnOrdinal(List<CarbonDimension> dimList, int ordinal) {
    for (CarbonDimension dimension : dimList) {
      if (dimension.getOrdinal() == ordinal) {
        return dimension;
      }
    }
    throw new RuntimeException("No Dimension Matches the ordinal value");
  }

  /**
   * @param tableName
   * @param columnName
   * @return
   */
  public CarbonColumn getColumnByName(String tableName, String columnName) {
    List<CarbonColumn> columns = createOrderColumn.get(tableName);
    Iterator<CarbonColumn> colItr = columns.iterator();
    while (colItr.hasNext()) {
      CarbonColumn col = colItr.next();
      if (col.getColName().equalsIgnoreCase(columnName)) {
        return col;
      }
    }
    return null;
  }

  /**
   * gets all children dimension for complex type
   *
   * @param dimName
   * @return list of child allDimensions
   */
  public List<CarbonDimension> getChildren(String dimName) {
    for (List<CarbonDimension> list : tableDimensionsMap.values()) {
      List<CarbonDimension> childDims = getChildren(dimName, list);
      if (childDims != null) {
        return childDims;
      }
    }
    return null;
  }

  /**
   * returns level 2 or more child allDimensions
   *
   * @param dimName
   * @param dimensions
   * @return list of child allDimensions
   */
  public List<CarbonDimension> getChildren(String dimName, List<CarbonDimension> dimensions) {
    for (CarbonDimension carbonDimension : dimensions) {
      if (carbonDimension.getColName().equals(dimName)) {
        return carbonDimension.getListOfChildDimensions();
      } else if (null != carbonDimension.getListOfChildDimensions()
          && carbonDimension.getListOfChildDimensions().size() > 0) {
        List<CarbonDimension> childDims =
            getChildren(dimName, carbonDimension.getListOfChildDimensions());
        if (childDims != null) {
          return childDims;
        }
      }
    }
    return null;
  }

  public BucketingInfo getBucketingInfo(String tableName) {
    return tableBucketMap.get(tableName);
  }

  public PartitionInfo getPartitionInfo(String tableName) {
    return tablePartitionMap.get(tableName);
  }

  public boolean isPartitionTable() {
    return null != tablePartitionMap.get(getTableName())
        && tablePartitionMap.get(getTableName()).getPartitionType() != PartitionType.NATIVE_HIVE;
  }

  public boolean isHivePartitionTable() {
    PartitionInfo partitionInfo = tablePartitionMap.get(getTableName());
    return null != partitionInfo && partitionInfo.getPartitionType() == PartitionType.NATIVE_HIVE;
  }

  public PartitionInfo getPartitionInfo() {
    return tablePartitionMap.get(getTableName());
  }

  /**
   * @return absolute table identifier
   */
  public AbsoluteTableIdentifier getAbsoluteTableIdentifier() {
    return tableInfo.getOrCreateAbsoluteTableIdentifier();
  }

  /**
   * @return carbon table identifier
   */
  public CarbonTableIdentifier getCarbonTableIdentifier() {
    return tableInfo.getOrCreateAbsoluteTableIdentifier().getCarbonTableIdentifier();
  }

  /**
   * gets partition count for this table
   * TODO: to be implemented while supporting partitioning
   */
  public int getPartitionCount() {
    return 1;
  }

  public int getBlockSizeInMB() {
    return blockSize;
  }

  /**
   * to get the normal dimension or the primitive dimension of the complex type
   *
   * @return primitive dimension of a table
   */
  public CarbonDimension getPrimitiveDimensionByName(String columnName) {
    List<CarbonDimension> dimList = tablePrimitiveDimensionsMap.get(tableUniqueName);
    for (CarbonDimension dim : dimList) {
      if (!dim.isInvisible() && dim.getColName().equalsIgnoreCase(columnName)) {
        return dim;
      }
    }
    return null;
  }

  /**
   * return all allDimensions in the table
   *
   * @return
   */
  public List<CarbonDimension> getAllDimensions() {
    return allDimensions;
  }

  /**
   * This method will all the visible allDimensions
   *
   * @param tableName
   */
  private void fillVisibleDimensions(String tableName) {
    List<CarbonDimension> visibleDimensions = new ArrayList<CarbonDimension>(allDimensions.size());
    for (CarbonDimension dimension : allDimensions) {
      if (!dimension.isInvisible()) {
        visibleDimensions.add(dimension);
      }
    }
    tableDimensionsMap.put(tableName, visibleDimensions);
  }

  /**
   * return all allMeasures in the table
   *
   * @return
   */
  public List<CarbonMeasure> getAllMeasures() {
    return allMeasures;
  }

  /**
   * This method will all the visible allMeasures
   *
   * @param tableName
   */
  private void fillVisibleMeasures(String tableName) {
    List<CarbonMeasure> visibleMeasures = new ArrayList<CarbonMeasure>(allMeasures.size());
    for (CarbonMeasure measure : allMeasures) {
      if (!measure.isInvisible()) {
        visibleMeasures.add(measure);
      }
    }
    tableMeasuresMap.put(tableName, visibleMeasures);
  }

  /**
   * Method to get the list of sort columns
   *
   * @param tableName
   * @return List of Sort column
   */
  public List<String> getSortColumns(String tableName) {
    List<String> sort_columsList = new ArrayList<String>(allDimensions.size());
    List<CarbonDimension> carbonDimensions = tableDimensionsMap.get(tableName);
    for (CarbonDimension dim : carbonDimensions) {
      if (dim.isSortColumn()) {
        sort_columsList.add(dim.getColName());
      }
    }
    return sort_columsList;
  }

  public int getNumberOfSortColumns() {
    return numberOfSortColumns;
  }

  public int getNumberOfNoDictSortColumns() {
    return numberOfNoDictSortColumns;
  }

  public TableInfo getTableInfo() {
    return tableInfo;
  }

  /**
   * Return true if this is a streaming table (table with property "streaming"="true" or "sink")
   */
  public boolean isStreamingSink() {
    String streaming = getTableInfo().getFactTable().getTableProperties().get("streaming");
    return streaming != null &&
        (streaming.equalsIgnoreCase("true") || streaming.equalsIgnoreCase("sink"));
  }

  /**
   * Return true if this is a streaming source (table with property "streaming"="source")
   */
  public boolean isStreamingSource() {
    String streaming = getTableInfo().getFactTable().getTableProperties().get("streaming");
    return streaming != null && streaming.equalsIgnoreCase("source");
  }

  /**
   * Return true if 'autoRefreshDataMap' is enabled, by default it is enabled
   */
  public boolean isAutoRefreshDataMap() {
    String refresh = getTableInfo().getFactTable().getTableProperties().get("autorefreshdatamap");
    return refresh == null || refresh.equalsIgnoreCase("true");
  }

  /**
   * whether this table has aggregation DataMap or not
   */
  public boolean hasAggregationDataMap() {
    List<DataMapSchema> dataMapSchemaList = tableInfo.getDataMapSchemaList();
    if (dataMapSchemaList != null && !dataMapSchemaList.isEmpty()) {
      for (DataMapSchema dataMapSchema : dataMapSchemaList) {
        if (dataMapSchema instanceof AggregationDataMapSchema) {
          return true;
        }
      }
    }
    return false;
  }

  public int getDimensionOrdinalMax() {
    return dimensionOrdinalMax;
  }

  public boolean hasDataMapSchema() {
    return hasDataMapSchema;
  }

  public DataMapSchema getDataMapSchema(String dataMapName) {
    List<DataMapSchema> dataMaps = tableInfo.getDataMapSchemaList();
    for (DataMapSchema dataMap : dataMaps) {
      if (dataMap.getDataMapName().equalsIgnoreCase(dataMapName)) {
        return dataMap;
      }
    }
    return null;
  }

  public boolean isChildDataMap() {
    return null != tableInfo.getParentRelationIdentifiers() &&
        !tableInfo.getParentRelationIdentifiers().isEmpty();
  }

  /**
   * Return true if this is an external table (table with property "_external"="true", this is
   * an internal table property set during table creation)
   */
  public boolean isExternalTable() {
    String external = tableInfo.getFactTable().getTableProperties().get("_external");
    return external != null && external.equalsIgnoreCase("true");
  }

  public boolean isFileLevelFormat() {
    String external = tableInfo.getFactTable().getTableProperties().get("_filelevelformat");
    return external != null && external.equalsIgnoreCase("true");
  }


  public long size() throws IOException {
    Map<String, Long> dataIndexSize = CarbonUtil.calculateDataIndexSize(this, true);
    Long dataSize = dataIndexSize.get(CarbonCommonConstants.CARBON_TOTAL_DATA_SIZE);
    if (dataSize == null) {
      dataSize = 0L;
    }
    Long indexSize = dataIndexSize.get(CarbonCommonConstants.CARBON_TOTAL_INDEX_SIZE);
    if (indexSize == null) {
      indexSize = 0L;
    }
    return dataSize + indexSize;
  }

  public void processFilterExpression(Expression filterExpression,
      boolean[] isFilterDimensions, boolean[] isFilterMeasures) {
    QueryModel.processFilterExpression(this, filterExpression, isFilterDimensions,
        isFilterMeasures);

    if (null != filterExpression) {
      // Optimize Filter Expression and fit RANGE filters is conditions apply.
      FilterOptimizer rangeFilterOptimizer =
          new RangeFilterOptmizer(filterExpression);
      rangeFilterOptimizer.optimizeFilter();
    }
  }

  /**
   * Resolve the filter expression.
   */
  public FilterResolverIntf resolveFilter(Expression filterExpression) {
    try {
      FilterExpressionProcessor filterExpressionProcessor = new FilterExpressionProcessor();
      return filterExpressionProcessor.getFilterResolver(
          filterExpression, getAbsoluteTableIdentifier());
    } catch (Exception e) {
      throw new RuntimeException("Error while resolving filter expression", e);
    }
  }

  /**
   * Create a {@link CarbonTableBuilder} to create {@link CarbonTable}
   */
  public static CarbonTableBuilder builder() {
    return new CarbonTableBuilder();
  }

  public boolean isTransactionalTable() {
    return isTransactionalTable;
  }

  public void setTransactionalTable(boolean transactionalTable) {
    isTransactionalTable = transactionalTable;
  }

  /**
   * methods returns true if operation is allowed for the corresponding datamap or not
   * if this operation makes datamap stale it is not allowed
   * @param carbonTable
   * @param operation
   * @return
   */
  public boolean canAllow(CarbonTable carbonTable, TableOperation operation) {
    try {
      List<TableDataMap> datamaps = DataMapStoreManager.getInstance().getAllDataMap(carbonTable);
      if (!datamaps.isEmpty()) {
        for (TableDataMap dataMap : datamaps) {
          DataMapFactory factoryClass =
              DataMapStoreManager.getInstance().getDataMapFactoryClass(
                  carbonTable, dataMap.getDataMapSchema());
          if (factoryClass.willBecomeStale(operation)) {
            return false;
          }
        }
      }
    } catch (Exception e) {
      // since method returns true or false and based on that calling function throws exception, no
      // need to throw the catched exception
      LOGGER.error(e.getMessage());
      return true;
    }
    return true;
  }

  /**
   * Get all index columns specified by dataMapSchema
   */
  public List<CarbonColumn> getIndexedColumns(DataMapSchema dataMapSchema)
      throws MalformedDataMapCommandException {
    String[] columns = dataMapSchema.getIndexColumns();
    List<CarbonColumn> indexColumn = new ArrayList<>(columns.length);
    for (String column : columns) {
      CarbonColumn carbonColumn = getColumnByName(getTableName(), column.trim().toLowerCase());
      if (carbonColumn == null) {
        throw new MalformedDataMapCommandException(String.format(
            "column '%s' does not exist in table. Please check create DataMap statement.",
            column));
      }
      if (carbonColumn.getColName().isEmpty()) {
        throw new MalformedDataMapCommandException(
            CarbonCommonConstants.INDEX_COLUMNS + " contains invalid column name");
      }
      indexColumn.add(carbonColumn);
    }
    return indexColumn;
  }

  /**
   * Whether this table supports flat folder structure, it means all data files directly written
   * under table path
   */
  public boolean isSupportFlatFolder() {
    boolean supportFlatFolder = Boolean.parseBoolean(CarbonCommonConstants.DEFAULT_FLAT_FOLDER);
    Map<String, String> tblProps = getTableInfo().getFactTable().getTableProperties();
    if (tblProps.containsKey(CarbonCommonConstants.FLAT_FOLDER)) {
      supportFlatFolder = tblProps.get(CarbonCommonConstants.FLAT_FOLDER).equalsIgnoreCase("true");
    }
    return supportFlatFolder;
  }

  /**
   * update the carbon table by using the passed tableInfo
   *
   * @param table
   * @param tableInfo
   */
  public static void updateTableByTableInfo(CarbonTable table, TableInfo tableInfo) {
    updateTableInfo(tableInfo);
    table.tableInfo = tableInfo;
    table.blockSize = tableInfo.getTableBlockSizeInMB();
    table.tableLastUpdatedTime = tableInfo.getLastUpdatedTime();
    table.tableUniqueName = tableInfo.getTableUniqueName();
    table.setTransactionalTable(tableInfo.isTransactionalTable());
    table.fillDimensionsAndMeasuresForTables(tableInfo.getFactTable());
    table.fillCreateOrderColumn(tableInfo.getFactTable().getTableName());
    if (tableInfo.getFactTable().getBucketingInfo() != null) {
      table.tableBucketMap.put(tableInfo.getFactTable().getTableName(),
          tableInfo.getFactTable().getBucketingInfo());
    }
    if (tableInfo.getFactTable().getPartitionInfo() != null) {
      table.tablePartitionMap.put(tableInfo.getFactTable().getTableName(),
          tableInfo.getFactTable().getPartitionInfo());
    }
    table.hasDataMapSchema =
        null != tableInfo.getDataMapSchemaList() && tableInfo.getDataMapSchemaList().size() > 0;
    setLocalDictInfo(table, tableInfo);
  }

  /**
   * This method sets whether the local dictionary is enabled or not, and the local dictionary
   * threshold, if not defined default value are considered.
   * @param table
   * @param tableInfo
   */
  private static void setLocalDictInfo(CarbonTable table, TableInfo tableInfo) {
    String isLocalDictionaryEnabled = tableInfo.getFactTable().getTableProperties()
        .get(CarbonCommonConstants.LOCAL_DICTIONARY_ENABLE);
    String localDictionaryThreshold = tableInfo.getFactTable().getTableProperties()
        .get(CarbonCommonConstants.LOCAL_DICTIONARY_THRESHOLD);
    if (null != isLocalDictionaryEnabled) {
      table.setLocalDictionaryEnabled(Boolean.parseBoolean(isLocalDictionaryEnabled));
      if (null != localDictionaryThreshold) {
        table.setLocalDictionaryThreshold(Integer.parseInt(localDictionaryThreshold));
      } else {
        table.setLocalDictionaryThreshold(
            Integer.parseInt(CarbonCommonConstants.LOCAL_DICTIONARY_THRESHOLD_DEFAULT));
      }
    } else {
      // in case of old tables, local dictionary enable property will not be present in
      // tableProperties, so disable the local dictionary generation
      table.setLocalDictionaryEnabled(Boolean.parseBoolean("false"));
    }
  }

  /**
   * Method to get the list of cached columns of the table
   *
   * @param tableName
   * @return
   */
  public List<String> getCachedColumns(String tableName) {
    List<String> cachedColsList = new ArrayList<>(tableDimensionsMap.size());
    String cacheColumns =
        tableInfo.getFactTable().getTableProperties().get(CarbonCommonConstants.COLUMN_META_CACHE);
    if (null != cacheColumns && !cacheColumns.isEmpty()) {
      List<CarbonDimension> carbonDimensions = tableDimensionsMap.get(tableName);
      List<CarbonMeasure> carbonMeasures = tableMeasuresMap.get(tableName);
      String[] cachedCols = cacheColumns.split(",");
      for (String column : cachedCols) {
        boolean found = false;
        // this will avoid adding the columns which have been dropped from the table
        for (CarbonDimension dimension : carbonDimensions) {
          if (dimension.getColName().equals(column)) {
            cachedColsList.add(column);
            found = true;
            break;
          }
        }
        // if column is not a dimension then check in measures
        if (!found) {
          for (CarbonMeasure measure : carbonMeasures) {
            if (measure.getColName().equals(column)) {
              cachedColsList.add(column);
              break;
            }
          }
        }
      }
    }
    return cachedColsList;
  }
}
