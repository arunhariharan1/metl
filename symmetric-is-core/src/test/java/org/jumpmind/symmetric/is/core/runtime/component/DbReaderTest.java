package org.jumpmind.symmetric.is.core.runtime.component;

import static org.junit.Assert.assertEquals;

import java.io.Serializable;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jumpmind.db.model.Column;
import org.jumpmind.db.model.Database;
import org.jumpmind.db.model.Table;
import org.jumpmind.db.platform.IDatabasePlatform;
import org.jumpmind.db.sql.DmlStatement;
import org.jumpmind.db.sql.DmlStatement.DmlType;
import org.jumpmind.db.sql.ISqlTemplate;
import org.jumpmind.symmetric.is.core.model.Component;
import org.jumpmind.symmetric.is.core.model.ComponentVersion;
import org.jumpmind.symmetric.is.core.model.FlowStep;
import org.jumpmind.symmetric.is.core.model.FlowVersion;
import org.jumpmind.symmetric.is.core.model.Folder;
import org.jumpmind.symmetric.is.core.model.Resource;
import org.jumpmind.symmetric.is.core.model.Setting;
import org.jumpmind.symmetric.is.core.runtime.EntityData;
import org.jumpmind.symmetric.is.core.runtime.Message;
import org.jumpmind.symmetric.is.core.runtime.StartupMessage;
import org.jumpmind.symmetric.is.core.runtime.flow.IMessageTarget;
import org.jumpmind.symmetric.is.core.runtime.resource.IResourceFactory;
import org.jumpmind.symmetric.is.core.runtime.resource.ResourceFactory;
import org.jumpmind.symmetric.is.core.runtime.resource.db.DataSourceResource;
import org.jumpmind.symmetric.is.core.utils.DbTestUtils;
import org.jumpmind.symmetric.is.core.utils.TestUtils;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
public class DbReaderTest {

    private static IResourceFactory resourceFactory;
    private static IDatabasePlatform platform;
    private static FlowStep readerFlowStep;

    @BeforeClass
    public static void setup() throws Exception {

        resourceFactory = new ResourceFactory();
        platform = createPlatformAndTestDatabase();
        readerFlowStep = createReaderFlowStep();
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testReaderParmsFromHeader() throws Exception {

        DbReader reader = new DbReader();
        Message inputMessage = new StartupMessage();
        Map<String, Serializable> msgParamMap = new HashMap<String, Serializable>();
        msgParamMap.put("param1", "abcde");
        msgParamMap.put("param2", new Integer(5));
        inputMessage.getHeader().setParameters(msgParamMap);
        Map<String, Object> inputParamMap = new HashMap<String, Object>();
        reader.setParamsFromInboundMsgAndRec(inputParamMap, inputMessage, null);

        assertEquals("abcde", inputParamMap.get("param1"));
        assertEquals(new Integer(5), inputParamMap.get("param2"));
    }

    @Test
    public void testReaderParmsFromMsgBody() throws Exception {

        DbReader reader = new DbReader();
        Message inputMessage = new StartupMessage();
        Map<String, Object> inputParamMap = new HashMap<String, Object>();
        EntityData inputRec = new EntityData();
        inputRec.put("param1", "fghij");
        inputRec.put("param2", new Integer(7));
        reader.setParamsFromInboundMsgAndRec(inputParamMap, inputMessage, inputRec);

        assertEquals("fghij", inputParamMap.get("param1"));
        assertEquals(new Integer(7), inputParamMap.get("param2"));
    }

    @Test
    public void testReaderParmsFromHeaderAndMsgBody() throws Exception {

        DbReader reader = new DbReader();
        Message inputMessage = new StartupMessage();
        Map<String, Serializable> msgParamMap = new HashMap<String, Serializable>();
        msgParamMap.put("param1", "abcde");
        msgParamMap.put("param2", new Integer(5));
        inputMessage.getHeader().setParameters(msgParamMap);
        EntityData inputRec = new EntityData();
        inputRec.put("param3", "fghij");
        inputRec.put("param4", new Integer(7));
        Map<String, Object> inputParamMap = new HashMap<String, Object>();
        reader.setParamsFromInboundMsgAndRec(inputParamMap, inputMessage, inputRec);

        assertEquals("abcde", inputParamMap.get("param1"));
        assertEquals(new Integer(5), inputParamMap.get("param2"));
        assertEquals("fghij", inputParamMap.get("param3"));
        assertEquals(new Integer(7), inputParamMap.get("param4"));
    }

    @Test
    public void testReaderFlowFromStartupMsg() throws Exception {

        DbReader reader = new DbReader();
        reader.setFlowStep(readerFlowStep);
        reader.start(null, resourceFactory);
        Message msg = new StartupMessage();
        MessageTarget msgTarget = new MessageTarget();
        reader.handle("test", msg, msgTarget);

        assertEquals(3, msgTarget.getTargetMessageCount());
        ArrayList<EntityData> payload = msgTarget.getMessage(0).getPayload();
        assertEquals("test row 1", payload.get(0).get("COL2"));
        assertEquals("test row x", payload.get(1).get("COLY"));
    }

    @Test
    public void testReaderFlowFromSingleContentMsg() throws Exception {

        DbReader reader = new DbReader();
        reader.setFlowStep(readerFlowStep);
        reader.start(null, resourceFactory);
        Message message = new Message("fake step id");
        message.setPayload(new ArrayList<EntityData>());
        ArrayList<EntityData> payload = message.getPayload();
        EntityData fakeRec = new EntityData("fake");
        payload.add(fakeRec);
        MessageTarget msgTarget = new MessageTarget();
        reader.handle("test", message, msgTarget);

        /* 3 messages - 2 rows a piece (one from table x, one from table y) */
        assertEquals(3, msgTarget.getTargetMessageCount());
        payload = msgTarget.getMessage(0).getPayload();
        assertEquals("test row 1", payload.get(0).get("COL2"));
        assertEquals("test row x", payload.get(1).get("COLY"));
    }

    @Test
    public void testReaderFlowFromMultipleContentMsgs() throws Exception {

    }

    private static FlowStep createReaderFlowStep() {

        Folder folder = TestUtils.createFolder("Test Folder");
        FlowVersion flow = TestUtils.createFlowVersion("TestFlow", folder);
        Component component = TestUtils.createComponent(DbReader.TYPE, false);
        Setting[] settingData = createReaderSettings();
        ComponentVersion componentVersion = TestUtils.createComponentVersion(component, null,
                settingData);
        componentVersion.setResource(createResource(createResourceSettings()));
        FlowStep readerComponent = new FlowStep();
        readerComponent.setFlowVersionId(flow.getId());
        readerComponent.setComponentVersionId(componentVersion.getId());
        readerComponent.setCreateBy("Test");
        readerComponent.setCreateTime(new Date());
        readerComponent.setLastModifyBy("Test");
        readerComponent.setLastModifyTime(new Date());
        readerComponent.setComponentVersion(componentVersion);
        return readerComponent;
    }

    private static Resource createResource(List<Setting> settings) {
        Resource resource = new Resource();
        Folder folder = TestUtils.createFolder("Test Folder Resource");
        resource.setName("Test Resource");
        resource.setFolderId("Test Folder Resource");
        resource.setType(DataSourceResource.TYPE);
        resource.setFolder(folder);
        resource.setSettings(settings);

        return resource;
    }

    private static Setting[] createReaderSettings() {

        Setting[] settingData = new Setting[2];
        settingData[0] = new Setting(DbReader.SQL,
                "select * From test_table_1 tt1 inner join test_table_2 tt2"
                + " on tt1.col1 = tt2.colx order by tt1.col1");
        settingData[1] = new Setting(DbReader.ROWS_PER_MESSAGE, "2");

        return settingData;
    }

    private static List<Setting> createResourceSettings() {
        List<Setting> settings = new ArrayList<Setting>(4);
        settings.add(new Setting(DataSourceResource.DB_POOL_DRIVER, "org.h2.Driver"));
        settings.add(new Setting(DataSourceResource.DB_POOL_URL, "jdbc:h2:file:build/dbs/testdb"));
        settings.add(new Setting(DataSourceResource.DB_POOL_USER, "jumpmind"));
        settings.add(new Setting(DataSourceResource.DB_POOL_PASSWORD, "jumpmind"));
        return settings;
    }

    private static IDatabasePlatform createPlatformAndTestDatabase() throws Exception {

        platform = DbTestUtils.createDatabasePlatform();
        Database database = createTestDatabase();
        platform.createDatabase(database, true, false);
        populateTestDatabase(platform, database);

        return platform;
    }

    private static Database createTestDatabase() {

        Table testTable1 = createTestTable1();
        Table testTable2 = createTestTable2();
        Database database = new Database();
        database.addTable(testTable1);
        database.addTable(testTable2);
        return database;
    }

    private static Table createTestTable1() {

        Table table = new Table("test_table_1");

        List<Column> columns = new ArrayList<Column>();
        columns.add(new Column("col1", true, Types.INTEGER, 4, 1));
        columns.add(new Column("col2", false, Types.VARCHAR, 50, 50));
        columns.add(new Column("col3", false, Types.DECIMAL, 9, 2));

        table.addColumns(columns);
        return table;
    }
    
    private static Table createTestTable2() {

        Table table = new Table("test_table_2");

        List<Column> columns = new ArrayList<Column>();
        columns.add(new Column("colx", true, Types.INTEGER, 4, 1));
        columns.add(new Column("coly", false, Types.VARCHAR, 50, 50));
        columns.add(new Column("colz", false, Types.DECIMAL, 9, 2));

        table.addColumns(columns);
        return table;
    }

    private static void populateTestDatabase(IDatabasePlatform platform, Database database) {

        ISqlTemplate template = platform.getSqlTemplate();
        DmlStatement statement = platform.createDmlStatement(DmlType.INSERT,
                database.findTable("test_table_1"), null);
        template.update(statement.getSql(),
                statement.getValueArray(new Object[] { 1, "test row 1", 7.7 }, new Object[] { 1 }));
        template.update(statement.getSql(),
                statement.getValueArray(new Object[] { 2, "test row 2", 8.8 }, new Object[] { 1 }));
        template.update(statement.getSql(),
                statement.getValueArray(new Object[] { 3, "test row 3", 9.9 }, new Object[] { 1 }));
        
        statement = platform.createDmlStatement(DmlType.INSERT, database.findTable("test_table_2"),null);
        template.update(statement.getSql(),
                statement.getValueArray(new Object[] { 1, "test row x", 7.7 }, new Object[] { 1 }));
        template.update(statement.getSql(),
                statement.getValueArray(new Object[] { 2, "test row y", 8.8 }, new Object[] { 1 }));
        template.update(statement.getSql(),
                statement.getValueArray(new Object[] { 3, "test row z", 9.9 }, new Object[] { 1 }));
        
    }

    class MessageTarget implements IMessageTarget {

        List<Message> targetMsgArray = new ArrayList<Message>();

        @Override
        public void put(Message message) {
            targetMsgArray.add(message);
        }

        public Message getMessage(int idx) {
            return targetMsgArray.get(idx);
        }

        public int getTargetMessageCount() {
            return targetMsgArray.size();
        }
    }
}
