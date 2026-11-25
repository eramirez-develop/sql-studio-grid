package com.quasera.sqlstudiogrid.connectivity;

import com.quasera.sqlstudiogrid.core.DbType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlScriptSplitterTest {

    private static List<String> split(String sql, DbType type) {
        return SqlScriptSplitter.split(sql, type.scriptOptions());
    }

    @Test
    void sqlServerSeparaSoloPorGo() {
        String script = "SELECT 1; SELECT 2;\nGO\nCREATE PROCEDURE p AS\nBEGIN\n  SELECT 3;\nEND\ngo\n";
        List<String> parts = split(script, DbType.SQL_SERVER);
        assertEquals(2, parts.size());
        assertEquals("SELECT 1; SELECT 2;", parts.get(0));
        assertEquals("CREATE PROCEDURE p AS\nBEGIN\n  SELECT 3;\nEND", parts.get(1));
    }

    @Test
    void goDentroDeCadenaOComentarioNoSepara() {
        String script = "SELECT 'a\nGO\nb'\n/* \nGO\n */\nSELECT 2";
        assertEquals(1, split(script, DbType.SYBASE).size());
    }

    @Test
    void goConContadorYRetornoDeCarro() {
        String script = "SELECT 1\r\nGO 5\r\nSELECT 2\r\n";
        List<String> parts = split(script, DbType.SQL_SERVER);
        assertEquals(List.of("SELECT 1", "SELECT 2"), parts);
    }

    @Test
    void mysqlSeparaPorPuntoYComaRespetandoCadenas() {
        List<String> parts = split("SELECT 'a;b'; SELECT `x;y` FROM t; -- fin;\n", DbType.MYSQL);
        assertEquals(List.of("SELECT 'a;b'", "SELECT `x;y` FROM t"), parts);
    }

    @Test
    void comillaEscapadaDuplicada() {
        List<String> parts = split("SELECT 'it''s; ok'; SELECT 2", DbType.MYSQL);
        assertEquals(List.of("SELECT 'it''s; ok'", "SELECT 2"), parts);
    }

    @Test
    void oracleBloquePlsqlHastaBarra() {
        String script = "SELECT * FROM dual;\n"
                + "CREATE OR REPLACE PROCEDURE p IS\nBEGIN\n  NULL;\nEND;\n/\n"
                + "SELECT 2 FROM dual;";
        List<String> parts = split(script, DbType.ORACLE);
        assertEquals(3, parts.size());
        assertEquals("SELECT * FROM dual", parts.get(0));
        assertEquals("CREATE OR REPLACE PROCEDURE p IS\nBEGIN\n  NULL;\nEND;", parts.get(1));
        assertEquals("SELECT 2 FROM dual", parts.get(2));
    }

    @Test
    void ignoraSentenciasSoloConComentarios() {
        assertEquals(List.of("-- hola\nSELECT 1"), split("-- hola\nSELECT 1;\n/* nada */;", DbType.MYSQL));
    }

    @Test
    void corchetesEnSqlServer() {
        assertEquals(1, split("SELECT [col\nGO\n] FROM t", DbType.SQL_SERVER).size());
    }
}
