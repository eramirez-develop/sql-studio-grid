package com.quasera.sqlstudiogrid.sql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlToolsTest {

    @Test
    void formateaConsultaSimple() {
        String out = SqlFormatter.format("select a,b from t where x=1 and y between 1 and 2 order by a");
        assertEquals("""
                SELECT
                    a,
                    b
                FROM t
                WHERE x = 1
                    AND y BETWEEN 1 AND 2
                ORDER BY a
                """, out);
    }

    @Test
    void formateaJoinYSubconsulta() {
        String out = SqlFormatter.format("select c.id, count(*) from clientes c inner join cuentas k "
                + "on k.cliente_id = c.id and k.estado = 'a' where c.id in (select id from vip) group by c.id");
        assertEquals("""
                SELECT
                    c.id,
                    COUNT(*)
                FROM clientes c
                INNER JOIN cuentas k ON k.cliente_id = c.id
                    AND k.estado = 'a'
                WHERE c.id IN (
                    SELECT
                        id
                    FROM vip
                )
                GROUP BY c.id
                """, out);
    }

    @Test
    void formateoConservaCadenasYComentarios() {
        String out = SqlFormatter.format("-- total\nselect top 10 nombre from t where n = N'a  b' and x = -1;");
        assertEquals("""
                -- total
                SELECT TOP 10
                    nombre
                FROM t
                WHERE n = N'a  b'
                    AND x = -1;
                """, out);
    }

    @Test
    void prefijoYCalificador() {
        String text = "SELECT c.nom FROM dbo.clientes c";
        int caret = "SELECT c.nom".length();
        assertEquals("nom", SqlCompletion.prefixAt(text, caret));
        assertEquals("c", SqlCompletion.qualifierAt(text, caret));
        assertEquals(null, SqlCompletion.qualifierAt("SELECT nom", 10));
        assertEquals("dbo", SqlCompletion.qualifierAt("SELECT * FROM [dbo].", 20));
    }

    @Test
    void detectaAlias() {
        Map<String, String> a = SqlCompletion.aliases(
                "SELECT a, b x FROM dbo.clientes c, productos pr INNER JOIN [ventas].[pedidos] AS p ON p.id = c.id WHERE 1=1");
        assertEquals("dbo.clientes", a.get("c"));
        assertEquals("dbo.clientes", a.get("clientes"));
        assertEquals("ventas.pedidos", a.get("p"));
        assertEquals("productos", a.get("pr"));
        assertEquals(null, a.get("where"));
        assertEquals(null, a.get("x"));
    }

    @Test
    void divideNombresCalificados() {
        assertEquals(List.of("db", "dbo", "tabla x"), SqlCompletion.splitQualified("db.[dbo].\"tabla x\""));
    }
}
