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
package org.apache.carbondata.processing.loading.parser.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.carbondata.core.metadata.datatype.DataType;
import org.apache.carbondata.core.metadata.datatype.DataTypes;
import org.apache.carbondata.core.metadata.schema.table.column.CarbonColumn;
import org.apache.carbondata.core.metadata.schema.table.column.CarbonDimension;
import org.apache.carbondata.processing.loading.DataField;
import org.apache.carbondata.processing.loading.complexobjects.ArrayObject;
import org.apache.carbondata.processing.loading.complexobjects.StructObject;
import org.apache.carbondata.processing.loading.parser.RowParser;

import org.apache.htrace.fasterxml.jackson.core.type.TypeReference;
import org.apache.htrace.fasterxml.jackson.databind.ObjectMapper;

public class JsonRowParser implements RowParser {

  private DataField[] dataFields;

  public JsonRowParser(DataField[] dataFields) {
    this.dataFields = dataFields;
  }

  @Override public Object[] parseRow(Object[] row) {
    try {
      return convertJsonToNoDictionaryToBytes((String) row[0]);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private Object[] convertJsonToNoDictionaryToBytes(String jsonString)
      throws IOException {
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      Map<String, Object> jsonNodeMap =
          objectMapper.readValue(jsonString, new TypeReference<Map<String, Object>>() {
          });
      return jsonToCarbonRecord(jsonNodeMap, dataFields);
    } catch (IOException e) {
      throw new IOException("Failed to parse Json String: " + e.getMessage());
    }
  }

  private Object[] jsonToCarbonRecord(Map<String, Object> jsonNodeMap, DataField[] dataFields) {
    List<Object> fields = new ArrayList<>();
    for (DataField dataField : dataFields) {
      Object field = jsonToCarbonObject(jsonNodeMap, dataField.getColumn());
      if (field != null) {
        fields.add(field);
      }
    }
    // use this array object to form carbonRow
    return fields.toArray();
  }

  private Object jsonToCarbonObject(Map<String, Object> jsonNodeMap, CarbonColumn column) {
    DataType type = column.getDataType();
    if (DataTypes.isArrayType(type)) {
      CarbonDimension carbonDimension = (CarbonDimension) column;
      int size = carbonDimension.getNumberOfChild();
      ArrayList array = (ArrayList) jsonNodeMap.get(extractChildColumnName(column));
      // stored as array in carbonObject
      Object[] arrayChildObjects = new Object[size];
      for (int i = 0; i < size; i++) {
        CarbonDimension childCol = carbonDimension.getListOfChildDimensions().get(i);
        arrayChildObjects[i] = jsonChildElementToCarbonChildElement(array.get(i), childCol);
      }
      return new ArrayObject(arrayChildObjects);
    } else if (DataTypes.isStructType(type)) {
      CarbonDimension carbonDimension = (CarbonDimension) column;
      int size = carbonDimension.getNumberOfChild();
      Map<String, Object> jsonMap =
          (Map<String, Object>) jsonNodeMap.get(extractChildColumnName(column));
      Object[] structChildObjects = new Object[size];
      for (int i = 0; i < size; i++) {
        CarbonDimension childCol = carbonDimension.getListOfChildDimensions().get(i);
        Object childObject =
            jsonChildElementToCarbonChildElement(jsonMap.get(extractChildColumnName(childCol)),
                childCol);
        if (childObject != null) {
          structChildObjects[i] = childObject;
        }
      }
      return new StructObject(structChildObjects);
    } else {
      // primitive type
      return jsonNodeMap.get(extractChildColumnName(column)).toString();
    }
  }

  private Object jsonChildElementToCarbonChildElement(Object childObject,
      CarbonDimension column) {
    DataType type = column.getDataType();
    if (DataTypes.isArrayType(type)) {
      int size = column.getNumberOfChild();
      ArrayList array = (ArrayList) childObject;
      // stored as array in carbonObject
      Object[] arrayChildObjects = new Object[size];
      for (int i = 0; i < size; i++) {
        CarbonDimension childCol = column.getListOfChildDimensions().get(i);
        arrayChildObjects[i] = jsonChildElementToCarbonChildElement(array.get(i), childCol);
      }
      return new ArrayObject(arrayChildObjects);
    } else if (DataTypes.isStructType(type)) {
      Map<String, Object> childFieldsMap = (Map<String, Object>) childObject;
      int size = column.getNumberOfChild();
      Object[] structChildObjects = new Object[size];
      for (int i = 0; i < size; i++) {
        CarbonDimension childCol = column.getListOfChildDimensions().get(i);
        Object child = jsonChildElementToCarbonChildElement(
            childFieldsMap.get(extractChildColumnName(childCol)), childCol);
        if (child != null) {
          structChildObjects[i] = child;
        }
      }
      return new StructObject(structChildObjects);
    } else {
      // primitive type
      return childObject.toString();
    }
  }
  private static String extractChildColumnName(CarbonColumn column) {
    String columnName = column.getColName();
    if (columnName.contains(".")) {
      // complex type child column names can be like following
      // a) struct type --> parent.child
      // b) array type --> parent.val.val...child [If create table flow]
      // c) array type --> parent.val0.val1...child [If SDK flow]
      // But json data's key is only child column name. So, extracting below
      String[] splits = columnName.split("\\.");
      columnName = splits[splits.length - 1];
    }
    return columnName;
  }

}
