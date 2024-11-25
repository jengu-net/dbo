package io.dbobjects.db.postgres;


import io.dbobjects.DBOApplicationConfig;
import org.junit.jupiter.api.Test;

public class SystemDbScriptsTest {

    @Test
    public void testSystemDbScriptsLoading() {
        SystemDbScripts.of(new DBOApplicationConfig());
    }

}
