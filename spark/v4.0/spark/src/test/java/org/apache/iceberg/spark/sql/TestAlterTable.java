/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.spark.sql;

import static org.apache.iceberg.CatalogUtil.ICEBERG_CATALOG_TYPE;
import static org.apache.iceberg.CatalogUtil.ICEBERG_CATALOG_TYPE_REST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.iceberg.spark.CatalogTestBase;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.types.Types.NestedField;
import org.apache.spark.SparkException;
import org.apache.spark.sql.AnalysisException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;

public class TestAlterTable extends CatalogTestBase {
  private final TableIdentifier renamedIdent =
      TableIdentifier.of(Namespace.of("default"), "table2");

  @BeforeEach
  public void createTable() {
    sql("CREATE TABLE %s (id bigint NOT NULL, data string) USING iceberg", tableName);
  }

  @AfterEach
  public void removeTable() {
    sql("DROP TABLE IF EXISTS %s", tableName);
    sql("DROP TABLE IF EXISTS %s2", tableName);
  }

  @TestTemplate
  public void testAddColumnNotNull() {
    assertThatThrownBy(() -> sql("ALTER TABLE %s ADD COLUMN c3 INT NOT NULL", tableName))
        .isInstanceOf(SparkException.class)
        .hasMessage(
            "Unsupported table change: Incompatible change: cannot add required column: c3");
  }

  @TestTemplate
  public void testAddColumn() {
    sql(
        "ALTER TABLE %s ADD COLUMN point struct<x: double NOT NULL, y: double NOT NULL> AFTER id",
        tableName);

    Types.StructType expectedSchema =
        Types.StructType.of(
            NestedField.required(1, "id", Types.LongType.get()),
            NestedField.optional(
                3,
                "point",
                Types.StructType.of(
                    NestedField.required(4, "x", Types.DoubleType.get()),
                    NestedField.required(5, "y", Types.DoubleType.get()))),
            NestedField.optional(2, "data", Types.StringType.get()));

    assertThat(validationCatalog.loadTable(tableIdent).schema().asStruct())
        .as("Schema should match expected")
        .isEqualTo(expectedSchema);

    sql("ALTER TABLE %s ADD COLUMN point.z double COMMENT 'May be null' FIRST", tableName);

    Types.StructType expectedSchema2 =
        Types.StructType.of(
            NestedField.required(1, "id", Types.LongType.get()),
            NestedField.optional(
                3,
                "point",
                Types.StructType.of(
                    NestedField.optional(6, "z", Types.DoubleType.get(), "May be null"),
                    NestedField.required(4, "x", Types.DoubleType.get()),
                    NestedField.required(5, "y", Types.DoubleType.get()))),
            NestedField.optional(2, "data", Types.StringType.get()));

    assertThat(validationCatalog.loadTable(tableIdent).schema().asStruct())
        .as("Schema should match expected")
        .isEqualTo(expectedSchema2);
  }

  @TestTemplate
  public void testAddColumnWithArray() {
    sql("ALTER TABLE %s ADD COLUMN data2 array<struct<a:INT,b:INT,c:int>>", tableName);
    // use the implicit column name 'element' to access member of array and add column d to struct.
    sql("ALTER TABLE %s ADD COLUMN data2.element.d int", tableName);
    Types.StructType expectedSchema =
        Types.StructType.of(
            NestedField.required(1, "id", Types.LongType.get()),
            NestedField.optional(2, "data", Types.StringType.get()),
            NestedField.optional(
                3,
                "data2",
                Types.ListType.ofOptional(
                    4,
                    Types.StructType.of(
                        NestedField.optional(5, "a", Types.IntegerType.get()),
                        NestedField.optional(6, "b", Types.IntegerType.get()),
                        NestedField.optional(7, "c", Types.IntegerType.get()),
                        NestedField.optional(8, "d", Types.IntegerType.get())))));
    assertThat(validationCatalog.loadTable(tableIdent).schema().asStruct())
        .as("Schema should match expected")
        .isEqualTo(expectedSchema);
  }

  @TestTemplate
  public void testAddColumnWithMap() {
    sql("ALTER TABLE %s ADD COLUMN data2 map<struct<x:INT>, struct<a:INT,b:INT>>", tableName);
    // use the implicit column name 'key' and 'value' to access member of map.
    // add column to value struct column
    sql("ALTER TABLE %s ADD COLUMN data2.value.c int", tableName);
    Types.StructType expectedSchema =
        Types.StructType.of(
            NestedField.required(1, "id", Types.LongType.get()),
            NestedField.optional(2, "data", Types.StringType.get()),
            NestedField.optional(
                3,
                "data2",
                Types.MapType.ofOptional(
                    4,
                    5,
                    Types.StructType.of(NestedField.optional(6, "x", Types.IntegerType.get())),
                    Types.StructType.of(
                        NestedField.optional(7, "a", Types.IntegerType.get()),
                        NestedField.optional(8, "b", Types.IntegerType.get()),
                        NestedField.optional(9, "c", Types.IntegerType.get())))));
    assertThat(validationCatalog.loadTable(tableIdent).schema().asStruct())
        .as("Schema should match expected")
        .isEqualTo(expectedSchema);

    // should not allow changing map key column
    assertThatThrownBy(() -> sql("ALTER TABLE %s ADD COLUMN data2.key.y int", tableName))
        .isInstanceOf(SparkException.class)
        .hasMessageStartingWith("Unsupported table change: Cannot add fields to map keys:");
  }

  @TestTemplate
  public void testAddColumnWithDefaultValuesUnsupported() {
    Object[][] cases =
            new Object[][] {
                    {"int", 42, 99},
                    {"bigint", 123456789L, 987654321L},
                    {"float", 3.14f, 9.81f},
                    {"double", 2.71828, 1.618},
                    {"decimal(9,2)", new BigDecimal("123.45"), new BigDecimal("99.99")},
                    {"string", "DEFAULT_STRING", "EXPLICIT_STRING"},
                    {"boolean", true, false}
            };
    for (Object[] testCase : cases) {
      String columnType = (String) testCase[0];
      Object defaultValue = testCase[1];
      Object explicitInsertedValue = testCase[2];
      String tableName = (catalogName.equals("spark_catalog") ? "" : catalogName + ".") + "default.add_column";
      sql("CREATE TABLE %s (ID INT) USING ICEBERG TBLPROPERTIES('format-version'='3') ", tableName);
      sql("INSERT INTO %s VALUES (1), (2)", tableName);
      String defaultValueSql = defaultValue.toString();
      if (columnType.equals("boolean")) {
        defaultValueSql = "true";
      } else if (columnType.equals("string")) {
        defaultValueSql = "'DEFAULT_STRING'";
      }
      sql("ALTER TABLE %s ADD COLUMN NEW_COL %s DEFAULT %s", tableName, columnType, defaultValueSql);
      Schema updatedSchema =
              validationCatalog
                      .loadTable(TableIdentifier.of("default", "add_column"))
                      .schema();
      Types.NestedField newField = updatedSchema.findField("NEW_COL");
      assertThat(newField).as("New field should exist").isNotNull();
      assertThat(newField.initialDefault()).isEqualTo(defaultValue);
      assertThat(newField.writeDefault()).isEqualTo(defaultValue);

      // Insert explicit and default values
      String explicitValSql =
              columnType.equals("boolean")
                      ? "false"
                      : (columnType.equals("string")
                      ? "'EXPLICIT_STRING'"
                      : explicitInsertedValue.toString());
      sql("INSERT INTO %s VALUES (3, %s)", tableName, explicitValSql);
      sql("INSERT INTO %s VALUES (4, DEFAULT)", tableName);

      List<Object[]> expectedRows =
              Arrays.asList(
                      row(1, defaultValue),
                      row(2, defaultValue),
                      row(3, explicitInsertedValue),
                      row(4, defaultValue));

      List<Object[]> actualRows = sql("SELECT * FROM %s ORDER BY id", tableName);

      assertEquals(
              String.format(
                      "Rows did not match for type: %s (default: %s, explicit: %s)",
                      columnType, defaultValue, explicitInsertedValue),
              expectedRows,
              actualRows);

      sql("DROP TABLE IF EXISTS %s", tableName);
    }
  }

  @TestTemplate
  public void testDropColumn() {
    sql("ALTER TABLE %s DROP COLUMN data", tableName);

    Types.StructType expectedSchema =
        Types.StructType.of(NestedField.required(1, "id", Types.LongType.get()));

    assertThat(validationCatalog.loadTable(tableIdent).schema().asStruct())
        .as("Schema should match expected")
        .isEqualTo(expectedSchema);
  }

  @TestTemplate
  public void testRenameColumn() {
    sql("ALTER TABLE %s RENAME COLUMN id TO row_id", tableName);

    Types.StructType expectedSchema =
        Types.StructType.of(
            NestedField.required(1, "row_id", Types.LongType.get()),
            NestedField.optional(2, "data", Types.StringType.get()));

    assertThat(validationCatalog.loadTable(tableIdent).schema().asStruct())
        .as("Schema should match expected")
        .isEqualTo(expectedSchema);
  }

  @TestTemplate
  public void testAlterColumnComment() {
    sql("ALTER TABLE %s ALTER COLUMN id COMMENT 'Record id'", tableName);

    Types.StructType expectedSchema =
        Types.StructType.of(
            NestedField.required(1, "id", Types.LongType.get(), "Record id"),
            NestedField.optional(2, "data", Types.StringType.get()));

    assertThat(validationCatalog.loadTable(tableIdent).schema().asStruct())
        .as("Schema should match expected")
        .isEqualTo(expectedSchema);
  }

  @TestTemplate
  public void testAlterColumnType() {
    sql("ALTER TABLE %s ADD COLUMN count int", tableName);
    sql("ALTER TABLE %s ALTER COLUMN count TYPE bigint", tableName);

    Types.StructType expectedSchema =
        Types.StructType.of(
            NestedField.required(1, "id", Types.LongType.get()),
            NestedField.optional(2, "data", Types.StringType.get()),
            NestedField.optional(3, "count", Types.LongType.get()));

    assertThat(validationCatalog.loadTable(tableIdent).schema().asStruct())
        .as("Schema should match expected")
        .isEqualTo(expectedSchema);
  }

  @TestTemplate
  public void testAlterColumnDropNotNull() {
    sql("ALTER TABLE %s ALTER COLUMN id DROP NOT NULL", tableName);

    Types.StructType expectedSchema =
        Types.StructType.of(
            NestedField.optional(1, "id", Types.LongType.get()),
            NestedField.optional(2, "data", Types.StringType.get()));

    assertThat(validationCatalog.loadTable(tableIdent).schema().asStruct())
        .as("Schema should match expected")
        .isEqualTo(expectedSchema);
  }

  @TestTemplate
  public void testAlterColumnSetNotNull() {
    // no-op changes are allowed
    sql("ALTER TABLE %s ALTER COLUMN id SET NOT NULL", tableName);

    Types.StructType expectedSchema =
        Types.StructType.of(
            NestedField.required(1, "id", Types.LongType.get()),
            NestedField.optional(2, "data", Types.StringType.get()));

    assertThat(validationCatalog.loadTable(tableIdent).schema().asStruct())
        .as("Schema should match expected")
        .isEqualTo(expectedSchema);

    assertThatThrownBy(() -> sql("ALTER TABLE %s ALTER COLUMN data SET NOT NULL", tableName))
        .isInstanceOf(AnalysisException.class)
        .hasMessageStartingWith("Cannot change nullable column to non-nullable: data");
  }

  @TestTemplate
  public void testAlterColumnPositionAfter() {
    sql("ALTER TABLE %s ADD COLUMN count int", tableName);
    sql("ALTER TABLE %s ALTER COLUMN count AFTER id", tableName);

    Types.StructType expectedSchema =
        Types.StructType.of(
            NestedField.required(1, "id", Types.LongType.get()),
            NestedField.optional(3, "count", Types.IntegerType.get()),
            NestedField.optional(2, "data", Types.StringType.get()));

    assertThat(validationCatalog.loadTable(tableIdent).schema().asStruct())
        .as("Schema should match expected")
        .isEqualTo(expectedSchema);
  }

  @TestTemplate
  public void testAlterColumnPositionFirst() {
    sql("ALTER TABLE %s ADD COLUMN count int", tableName);
    sql("ALTER TABLE %s ALTER COLUMN count FIRST", tableName);

    Types.StructType expectedSchema =
        Types.StructType.of(
            NestedField.optional(3, "count", Types.IntegerType.get()),
            NestedField.required(1, "id", Types.LongType.get()),
            NestedField.optional(2, "data", Types.StringType.get()));

    assertThat(validationCatalog.loadTable(tableIdent).schema().asStruct())
        .as("Schema should match expected")
        .isEqualTo(expectedSchema);
  }

  @TestTemplate
  public void testTableRename() {
    assumeThat(catalogConfig.get(ICEBERG_CATALOG_TYPE))
        .as(
            "need to fix https://github.com/apache/iceberg/issues/11154 before enabling this for the REST catalog")
        .isNotEqualTo(ICEBERG_CATALOG_TYPE_REST);
    assumeThat(validationCatalog)
        .as("Hadoop catalog does not support rename")
        .isNotInstanceOf(HadoopCatalog.class);

    assertThat(validationCatalog.tableExists(tableIdent)).as("Initial name should exist").isTrue();
    assertThat(validationCatalog.tableExists(renamedIdent))
        .as("New name should not exist")
        .isFalse();

    sql("ALTER TABLE %s RENAME TO %s2", tableName, tableName);

    assertThat(validationCatalog.tableExists(tableIdent))
        .as("Initial name should not exist")
        .isFalse();
    assertThat(validationCatalog.tableExists(renamedIdent)).as("New name should exist").isTrue();
  }

  @TestTemplate
  public void testSetTableProperties() {
    sql("ALTER TABLE %s SET TBLPROPERTIES ('prop'='value')", tableName);

    assertThat(validationCatalog.loadTable(tableIdent).properties())
        .as("Should have the new table property")
        .containsEntry("prop", "value");

    sql("ALTER TABLE %s UNSET TBLPROPERTIES ('prop')", tableName);

    assertThat(validationCatalog.loadTable(tableIdent).properties())
        .as("Should not have the removed table property")
        .doesNotContainKey("prop");

    String[] reservedProperties = new String[] {"sort-order", "identifier-fields"};
    for (String reservedProp : reservedProperties) {
      assertThatThrownBy(
              () -> sql("ALTER TABLE %s SET TBLPROPERTIES ('%s'='value')", tableName, reservedProp))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageStartingWith(
              "Cannot specify the '%s' because it's a reserved table property", reservedProp);
    }
  }
}
