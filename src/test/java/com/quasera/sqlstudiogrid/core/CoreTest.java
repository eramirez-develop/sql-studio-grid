package com.quasera.sqlstudiogrid.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CoreTest {

    @TempDir
    Path tmp;

    @Test
    void urlsPorMotor() {
        assertEquals("jdbc:sqlserver://srv:1433;databaseName=bd;encrypt=true;trustServerCertificate=true",
                DbType.SQL_SERVER.buildUrl("srv", 1433, "bd"));
        assertEquals("jdbc:jtds:sybase://srv:5000/bd", DbType.SYBASE.buildUrl("srv", 5000, "bd"));
        assertEquals("jdbc:oracle:thin:@//srv:1521/ORCL", DbType.ORACLE.buildUrl("srv", 1521, "ORCL"));
        assertEquals("jdbc:mysql://srv:3306/bd", DbType.MYSQL.buildUrl("srv", 3306, "bd"));
    }

    @Test
    void consultaDeVistaPrevia() {
        assertEquals("SELECT TOP 200 * FROM [dbo].[Clientes]", DbType.SQL_SERVER.previewSql("dbo", "Clientes", 200));
        assertEquals("SELECT * FROM \"HR\".\"EMP\" FETCH FIRST 10 ROWS ONLY", DbType.ORACLE.previewSql("HR", "EMP", 10));
        assertEquals("SELECT * FROM `ventas` LIMIT 5", DbType.MYSQL.previewSql("", "ventas", 5));
    }

    @Test
    void cifradoIdaYVuelta() throws Exception {
        CredentialCipher cipher = new CredentialCipher(tmp.resolve("k.key"));
        String enc = cipher.encrypt("clave-secreta-ñ");
        assertNotEquals("clave-secreta-ñ", enc);
        assertEquals("clave-secreta-ñ", cipher.decrypt(enc));
        // misma clave al recargar desde el archivo
        assertEquals("clave-secreta-ñ", new CredentialCipher(tmp.resolve("k.key")).decrypt(enc));
    }

    @Test
    void guardarYCargarPerfiles() throws Exception {
        CredentialCipher cipher = new CredentialCipher(tmp.resolve("k.key"));
        ProfileStore store = new ProfileStore(tmp.resolve("c.properties"), cipher);
        ConnectionProfile p = new ConnectionProfile(null, "Banco QA", DbType.SYBASE, "10.0.0.5", 5000,
                "core", "usr", "p@ss", "");
        store.save(List.of(p));

        assertFalse(Files.readString(tmp.resolve("c.properties")).contains("p@ss"));
        List<ConnectionProfile> loaded = store.load();
        assertEquals(List.of(p), loaded);
    }
}
