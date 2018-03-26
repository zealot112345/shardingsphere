/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingjdbc.dbtest.asserts;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;

import io.shardingjdbc.core.api.ShardingDataSourceFactory;
import io.shardingjdbc.dbtest.config.bean.AssertDDLDefinition;
import io.shardingjdbc.dbtest.config.bean.AssertDMLDefinition;
import io.shardingjdbc.dbtest.data.ColumnDefinition;
import io.shardingjdbc.dbtest.init.InItCreateSchema;
import io.shardingjdbc.test.sql.SQLCasesLoader;
import org.junit.Assert;
import org.xml.sax.SAXException;

import io.shardingjdbc.core.jdbc.core.ShardingContext;
import io.shardingjdbc.core.jdbc.core.datasource.ShardingDataSource;
import io.shardingjdbc.core.rule.ShardingRule;
import io.shardingjdbc.dbtest.common.DatabaseUtil;
import io.shardingjdbc.dbtest.common.PathUtil;
import io.shardingjdbc.dbtest.config.bean.AssertDQLDefinition;
import io.shardingjdbc.dbtest.config.bean.AssertsDefinition;
import io.shardingjdbc.dbtest.data.AnalyzeDataset;
import io.shardingjdbc.dbtest.data.DatasetDatabase;
import io.shardingjdbc.dbtest.data.DatasetDefinition;
import io.shardingjdbc.dbtest.exception.DbTestException;

public class AssertEngine {
    
    public static final Map<String, AssertsDefinition> ASSERTDEFINITIONMAPS = new HashMap<>();
    
    /**
     * add check use cases.
     *
     * @param assertPath        Check the use case storage path
     * @param assertsDefinition Check use case definitions
     */
    public static void addAssertDefinition(final String assertPath, final AssertsDefinition assertsDefinition) {
        ASSERTDEFINITIONMAPS.put(assertPath, assertsDefinition);
    }
    
    /**
     * Execution use case.
     *
     * @param path Check the use case storage path
     * @param id   Unique primary key for a use case
     * @return Successful implementation
     */
    public static boolean runAssert(final String path, final String id) {
        
        AssertsDefinition assertsDefinition = ASSERTDEFINITIONMAPS.get(path);
        
        String rootPath = path.substring(0, path.lastIndexOf(File.separator) + 1);
        assertsDefinition.setPath(rootPath);
        
        try {
            
            DataSource dataSource = getDataSource(PathUtil.getPath(assertsDefinition.getShardingRuleConfig(), rootPath));
            
            ShardingContext shardingContext = getShardingContext((ShardingDataSource) dataSource);
            Map<String, DataSource> dataSourceMaps = shardingContext.getDataSourceMap();
            
            List<String> dbs = new ArrayList<>();
            for (String s : dataSourceMaps.keySet()) {
                dbs.add(s);
            }
            
            // dql run
            for (AssertDQLDefinition each : assertsDefinition.getAssertDQL()) {
                if (id.equals(each.getId())) {
                    AssertDQLDefinition anAssert = each;
                    
                    String rootsql = anAssert.getSql();
                    rootsql = SQLCasesLoader.getSql(rootsql);
                    String initDataFile = PathUtil.getPath(anAssert.getInitDataFile(), rootPath);
                    Map<String, DatasetDefinition> mapDatasetDefinition = new HashMap<>();
                    Map<String, String> sqls = new HashMap<>();
                    getInitDatas(dbs, initDataFile, mapDatasetDefinition, sqls);
                    
                    if (mapDatasetDefinition.isEmpty()) {
                        throw new DbTestException(path + "  Use cases cannot be parsed");
                    }
                    
                    if (sqls.isEmpty()) {
                        throw new DbTestException(path + "  The use case cannot initialize the data");
                    }
                    
                    if (DatabaseUtil.isSelect(rootsql)) {
                        initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
                        try {
                            
                            doSelectUsePreparedStatement(rootPath, dataSource, anAssert, rootsql);
                            doSelectUsePreparedStatementToExecuteSelect(rootPath, dataSource, anAssert, rootsql);
                            
                            doSelectUseStatement(rootPath, dataSource, anAssert, rootsql);
                            doSelectUseStatementToExecuteSelect(rootPath, dataSource, anAssert, rootsql);
                        } finally {
                            clearTableData(dataSourceMaps, mapDatasetDefinition);
                        }
                        
                    }
                }
            }
            
            // dml run
            for (AssertDMLDefinition each : assertsDefinition.getAssertDML()) {
                if (id.equals(each.getId())) {
                    AssertDMLDefinition anAssert = each;
                    String rootsql = anAssert.getSql();
                    rootsql = SQLCasesLoader.getSql(rootsql);
                    String initDataFile = PathUtil.getPath(anAssert.getInitDataFile(), rootPath);
                    Map<String, DatasetDefinition> mapDatasetDefinition = new HashMap<>();
                    Map<String, String> sqls = new HashMap<>();
                    getInitDatas(dbs, initDataFile, mapDatasetDefinition, sqls);
                    
                    if (mapDatasetDefinition.isEmpty()) {
                        throw new DbTestException(path + "  Use cases cannot be parsed");
                    }
                    
                    if (sqls.isEmpty()) {
                        throw new DbTestException(path + "  The use case cannot initialize the data");
                    }
                    
                    doUpdateUseStatementToExecuteUpdate(rootPath, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls);
                    
                    doUpdateUseStatementToExecute(rootPath, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls);
                    
                    doUpdateUsePreparedStatementToExecuteUpdate(rootPath, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls);
                    
                    doUpdateUsePreparedStatementToExecute(rootPath, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls);
                    
                }
            }
            
            // ddl run
            for (AssertDDLDefinition each : assertsDefinition.getAssertDDL()) {
                if (id.equals(each.getId())) {
                    AssertDDLDefinition anAssert = each;
                    String rootsql = anAssert.getSql();
                    rootsql = SQLCasesLoader.getSql(rootsql);
                    String initDataFile = PathUtil.getPath(anAssert.getInitDataFile(), rootPath);
                    Map<String, DatasetDefinition> mapDatasetDefinition = new HashMap<>();
                    Map<String, String> sqls = new HashMap<>();
                    getInitDatas(dbs, initDataFile, mapDatasetDefinition, sqls);
                    
                    if (mapDatasetDefinition.isEmpty()) {
                        throw new DbTestException(path + "  Use cases cannot be parsed");
                    }
                    
                    if (sqls.isEmpty()) {
                        throw new DbTestException(path + "  The use case cannot initialize the data");
                    }
                    
                    doUpdateUseStatementToExecuteUpdateDDL(rootPath, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls);
                    
                    doUpdateUseStatementToExecuteDDL(rootPath, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls);
                    
                    doUpdateUsePreparedStatementToExecuteUpdateDDL(rootPath, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls);
                    
                    doUpdateUsePreparedStatementToExecuteDDL(rootPath, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls);
                    
                }
            }
            
        } catch (NoSuchFieldException | IllegalAccessException | ParseException | XPathExpressionException | SQLException | ParserConfigurationException | SAXException | IOException e) {
            throw new DbTestException(e);
        }
        return true;
    }
    
    private static void doUpdateUsePreparedStatementToExecute(final String rootPath, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDMLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection();) {
                boolean actual = DatabaseUtil.updateUsePreparedStatementToExecute(con, rootsql,
                        anAssert.getParameters());
                String expectedDataFile = PathUtil.getPath(anAssert.getExpectedDataFile(), rootPath);
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile));
                
                Assert.assertEquals("Update error", false, actual);
                
                String checksql = anAssert.getExpectedSql();
                checksql = SQLCasesLoader.getSql(checksql);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(con, checksql,
                        anAssert.getParameters());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
            }
        } finally {
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doUpdateUsePreparedStatementToExecuteDDL(final String rootPath, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDDLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection()) {
                InItCreateSchema.dropTable();
                InItCreateSchema.createTable();
                DatabaseUtil.updateUsePreparedStatementToExecute(con, rootsql,
                        anAssert.getParameters());
                String expectedDataFile = PathUtil.getPath(anAssert.getExpectedDataFile(), rootPath);
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile));
                
                String table = anAssert.getTable();
                List<ColumnDefinition> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions,table);
            }
        } finally {
            InItCreateSchema.dropTable();
            InItCreateSchema.createTable();
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doUpdateUsePreparedStatementToExecuteUpdate(final String rootPath, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDMLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection();) {
                int actual = DatabaseUtil.updateUsePreparedStatementToExecuteUpdate(con, rootsql,
                        anAssert.getParameters());
                String expectedDataFile = PathUtil.getPath(anAssert.getExpectedDataFile(), rootPath);
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile));
                
                Assert.assertEquals("Update row number error", anAssert.getExpectedUpdate().intValue(), actual);
                
                String checksql = anAssert.getExpectedSql();
                checksql = SQLCasesLoader.getSql(checksql);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(con, checksql,
                        anAssert.getParameters());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
            }
        } finally {
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doUpdateUsePreparedStatementToExecuteUpdateDDL(final String rootPath, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDDLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection()) {
                InItCreateSchema.dropTable();
                InItCreateSchema.createTable();
                DatabaseUtil.updateUsePreparedStatementToExecuteUpdate(con, rootsql,
                        anAssert.getParameters());
                String expectedDataFile = PathUtil.getPath(anAssert.getExpectedDataFile(), rootPath);
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile));
                
                
                String table = anAssert.getTable();
                List<ColumnDefinition> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions,table);
            }
        } finally {
            InItCreateSchema.dropTable();
            InItCreateSchema.createTable();
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doUpdateUseStatementToExecute(final String rootPath, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDMLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection();) {
                boolean actual = DatabaseUtil.updateUseStatementToExecute(con, rootsql, anAssert.getParameters());
                
                String expectedDataFile = PathUtil.getPath(anAssert.getExpectedDataFile(), rootPath);
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile));
                
                Assert.assertEquals("Update error", false, actual);
                
                String checksql = anAssert.getExpectedSql();
                checksql = SQLCasesLoader.getSql(checksql);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(con, checksql,
                        anAssert.getParameters());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
            }
        } finally {
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doUpdateUseStatementToExecuteDDL(final String rootPath, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDDLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection()) {
                InItCreateSchema.dropTable();
                InItCreateSchema.createTable();
                DatabaseUtil.updateUseStatementToExecute(con, rootsql, anAssert.getParameters());
                
                String expectedDataFile = PathUtil.getPath(anAssert.getExpectedDataFile(), rootPath);
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile));
                
                String table = anAssert.getTable();
                List<ColumnDefinition> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions,table);
            }
        } finally {
            InItCreateSchema.dropTable();
            InItCreateSchema.createTable();
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doUpdateUseStatementToExecuteUpdate(final String rootPath, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDMLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection();) {
                int actual = DatabaseUtil.updateUseStatementToExecuteUpdate(con, rootsql, anAssert.getParameters());
                String expectedDataFile = PathUtil.getPath(anAssert.getExpectedDataFile(), rootPath);
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile));
                
                Assert.assertEquals("Update row number error", anAssert.getExpectedUpdate().intValue(), actual);
                
                String checksql = anAssert.getExpectedSql();
                checksql = SQLCasesLoader.getSql(checksql);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(con, checksql,
                        anAssert.getParameters());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
                
            }
        } finally {
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doUpdateUseStatementToExecuteUpdateDDL(final String rootPath, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDDLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection()) {
                InItCreateSchema.dropTable();
                InItCreateSchema.createTable();
                DatabaseUtil.updateUseStatementToExecuteUpdate(con, rootsql, anAssert.getParameters());
                String expectedDataFile = PathUtil.getPath(anAssert.getExpectedDataFile(), rootPath);
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile));
                
                String table = anAssert.getTable();
                List<ColumnDefinition> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions,table);
            }
        } finally {
            InItCreateSchema.dropTable();
            InItCreateSchema.createTable();
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doSelectUseStatement(final String rootPath, final DataSource dataSource, final AssertDQLDefinition anAssert, final String rootsql) throws SQLException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection con = dataSource.getConnection();) {
            DatasetDatabase ddStatement = DatabaseUtil.selectUseStatement(con, rootsql,
                    anAssert.getParameters());
            String expectedDataFile = PathUtil.getPath(anAssert.getExpectedDataFile(), rootPath);
            DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile));
            
            DatabaseUtil.assertDatas(checkDataset, ddStatement);
        }
    }
    
    private static void doSelectUseStatementToExecuteSelect(final String rootPath, final DataSource dataSource, final AssertDQLDefinition anAssert, final String rootsql) throws SQLException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection con = dataSource.getConnection();) {
            DatasetDatabase ddStatement = DatabaseUtil.selectUseStatementToExecuteSelect(con, rootsql,
                    anAssert.getParameters());
            String expectedDataFile = PathUtil.getPath(anAssert.getExpectedDataFile(), rootPath);
            DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile));
            
            DatabaseUtil.assertDatas(checkDataset, ddStatement);
        }
    }
    
    private static void doSelectUsePreparedStatement(final String rootPath, final DataSource dataSource, final AssertDQLDefinition anAssert, final String rootsql) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection con = dataSource.getConnection();) {
            DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(con, rootsql,
                    anAssert.getParameters());
            
            String expectedDataFile = PathUtil.getPath(anAssert.getExpectedDataFile(), rootPath);
            DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile));
            
            DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
        }
    }
    
    private static void doSelectUsePreparedStatementToExecuteSelect(final String rootPath, final DataSource dataSource, final AssertDQLDefinition anAssert, final String rootsql) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection con = dataSource.getConnection();) {
            DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatementToExecuteSelect(con, rootsql,
                    anAssert.getParameters());
            
            String expectedDataFile = PathUtil.getPath(anAssert.getExpectedDataFile(), rootPath);
            DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile));
            
            DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
        }
    }
    
    private static void getInitDatas(final List<String> dbs, final String initDataFile,
                                     final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls)
            throws IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        for (String each : dbs) {
            String tempPath = initDataFile + "/" + each + ".xml";
            File file = new File(tempPath);
            if (file.exists()) {
                DatasetDefinition datasetDefinition = AnalyzeDataset.analyze(file);
                mapDatasetDefinition.put(each, datasetDefinition);
                
                Map<String, List<Map<String, String>>> datas = datasetDefinition.getDatas();
                for (Map.Entry<String, List<Map<String, String>>> eachEntry : datas.entrySet()) {
                    String sql = DatabaseUtil.analyzeSql(eachEntry.getKey(), eachEntry.getValue().get(0));
                    sqls.put(eachEntry.getKey(), sql);
                }
            }
        }
    }
    
    
    private static void clearTableData(final Map<String, DataSource> dataSourceMaps,
                                       final Map<String, DatasetDefinition> mapDatasetDefinition) throws SQLException {
        for (Map.Entry<String, DataSource> eachEntry : dataSourceMaps.entrySet()) {
            
            DataSource dataSource1 = eachEntry.getValue();
            DatasetDefinition datasetDefinition = mapDatasetDefinition.get(eachEntry.getKey());
            Map<String, List<Map<String, String>>> datas = datasetDefinition.getDatas();
            
            for (Map.Entry<String, List<Map<String, String>>> eachListEntry : datas.entrySet()) {
                try (Connection conn = dataSource1.getConnection()) {
                    DatabaseUtil.cleanAllUsePreparedStatement(conn, eachListEntry.getKey());
                }
            }
            
        }
    }
    
    private static void initTableData(final Map<String, DataSource> dataSourceMaps, final Map<String, String> sqls,
                                      final Map<String, DatasetDefinition> mapDatasetDefinition) throws SQLException, ParseException {
        for (Map.Entry<String, DataSource> eachDataSourceEntry : dataSourceMaps.entrySet()) {
            DataSource dataSource1 = eachDataSourceEntry.getValue();
            DatasetDefinition datasetDefinition = mapDatasetDefinition.get(eachDataSourceEntry.getKey());
            Map<String, List<ColumnDefinition>> configs = datasetDefinition.getMetadatas();
            Map<String, List<Map<String, String>>> datas = datasetDefinition.getDatas();
            
            for (Map.Entry<String, List<Map<String, String>>> eachListEntry : datas.entrySet()) {
                try (Connection conn = dataSource1.getConnection()) {
                    DatabaseUtil.insertUsePreparedStatement(conn, sqls.get(eachListEntry.getKey()),
                            datas.get(eachListEntry.getKey()), configs.get(eachListEntry.getKey()));
                }
            }
        }
    }
    
    
    /**
     * Generating DataSource from yaml.
     *
     * @param path path
     * @return DataSource
     * @throws IOException  IOException
     * @throws SQLException SQLException
     */
    public static DataSource getDataSource(final String path) throws IOException, SQLException {
        return ShardingDataSourceFactory.createDataSource(new File(path));
    }
    
    /**
     * According to the sub DataSource set of shardingDataSource.
     *
     * @param shardingDataSource shardingDataSource
     * @return DataSource map
     * @throws NoSuchFieldException     NoSuchFieldException
     * @throws SecurityException        SecurityException
     * @throws IllegalArgumentException IllegalArgumentException
     * @throws IllegalAccessException   IllegalAccessException
     */
    public static Map<String, DataSource> getDataSourceMap(final ShardingDataSource shardingDataSource)
            throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
        ShardingContext shardingContext = getShardingContext(shardingDataSource);
        return shardingContext.getDataSourceMap();
    }
    
    /**
     * According to ShardingRule in shardingDataSource.
     *
     * @param shardingDataSource shardingDataSource
     * @return ShardingRule
     * @throws NoSuchFieldException     NoSuchFieldException
     * @throws SecurityException        SecurityException
     * @throws IllegalArgumentException IllegalArgumentException
     * @throws IllegalAccessException   IllegalAccessException
     */
    public static ShardingRule getShardingRule(final ShardingDataSource shardingDataSource)
            throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
        ShardingContext shardingContext = getShardingContext(shardingDataSource);
        return shardingContext.getShardingRule();
    }
    
    /**
     * According to ShardingContext in shardingDataSource.
     *
     * @param shardingDataSource shardingDataSource
     * @return ShardingContext
     * @throws NoSuchFieldException     NoSuchFieldException
     * @throws SecurityException        SecurityException
     * @throws IllegalArgumentException IllegalArgumentException
     * @throws IllegalAccessException   IllegalAccessException
     */
    public static ShardingContext getShardingContext(final ShardingDataSource shardingDataSource)
            throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
        Field field = shardingDataSource.getClass().getDeclaredField("shardingContext");
        field.setAccessible(true);
        return (ShardingContext) field.get(shardingDataSource);
    }
}
