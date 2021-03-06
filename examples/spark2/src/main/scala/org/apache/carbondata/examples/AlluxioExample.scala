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

package org.apache.carbondata.examples

import org.apache.spark.sql.SparkSession

import org.apache.carbondata.core.constants.CarbonCommonConstants
import org.apache.carbondata.core.datastore.impl.FileFactory
import org.apache.carbondata.core.util.CarbonProperties
import org.apache.carbondata.examples.util.ExampleUtils


/**
 * configure alluxio:
 * 1.start alluxio
 * 2.upload the jar :"/alluxio_path/core/client/target/
 * alluxio-core-client-YOUR-VERSION-jar-with-dependencies.jar"
 * 3.Get more detail at:http://www.alluxio.org/docs/master/en/Running-Spark-on-Alluxio.html
 */

object AlluxioExample {
  def main(args: Array[String]) {
    val spark = ExampleUtils.createCarbonSession("AlluxioExample")
    exampleBody(spark)
    spark.close()
  }

  def exampleBody(spark : SparkSession): Unit = {
    spark.sparkContext.hadoopConfiguration.set("fs.alluxio.impl", "alluxio.hadoop.FileSystem")
    FileFactory.getConfiguration.set("fs.alluxio.impl", "alluxio.hadoop.FileSystem")

    // Specify date format based on raw data
    CarbonProperties.getInstance()
      .addProperty(CarbonCommonConstants.CARBON_DATE_FORMAT, "yyyy/MM/dd")

    spark.sql("DROP TABLE IF EXISTS alluxio_table")

    spark.sql("""
           CREATE TABLE IF NOT EXISTS alluxio_table
           (ID Int, date Date, country String,
           name String, phonetype String, serialname String, salary Int)
           STORED BY 'carbondata'
           """)

    spark.sql(s"""
           LOAD DATA LOCAL INPATH 'alluxio://localhost:19998/data.csv' into table alluxio_table
           """)

    spark.sql("""
           SELECT country, count(salary) AS amount
           FROM alluxio_table
           WHERE country IN ('china','france')
           GROUP BY country
           """).show()

    spark.sql("DROP TABLE IF EXISTS alluxio_table")
  }
}
