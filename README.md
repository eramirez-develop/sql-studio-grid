# SQL Studio Grid

Cliente de bases de datos de escritorio, inspirado en el flujo de trabajo de DataGrip,
enfocado en **SQL Server, Sybase ASE, Oracle y MySQL**. Hecho en Java 25 + Swing (tema FlatLaf).

> Versión 0.2.0 — Fases 1 y 2

## Funciones

- **Conexiones guardadas** con contraseña cifrada (AES-256-GCM) en `~/.sql-studio-grid/`.
- **Probar conexión** antes de guardar; URL JDBC personalizada opcional.
- **Explorador** con carga perezosa: conexión → esquemas → tablas / vistas / procedimientos → columnas.
  - Doble clic en una tabla: abre una consola con los primeros 200 registros.
  - Menú contextual: nueva consola, copiar nombre, refrescar, desconectar, editar, eliminar.
- **Consola SQL** (una sesión por pestaña):
  - Resaltado de sintaxis, números de línea, deshacer/rehacer.
  - Ejecuta la selección o todo el script (`Ctrl+Enter` / `F5`), cancelar con `Esc`.
  - Separación de lotes por motor: `GO` en SQL Server/Sybase (como SSMS), `;` y `/` en Oracle
    (respeta bloques PL/SQL), `;` en MySQL.
  - Auto-commit activable, con botones Commit / Rollback.
  - Límite de filas configurable.
- **Resultados**: una pestaña por resultado, mensajes (incluye `PRINT`), ordenar columnas,
  `<null>` visible, copiar celdas/filas, **exportar a CSV** (UTF-8, compatible con Excel).
- Abrir y guardar scripts `.sql`.

### Fase 2

- **Autocompletado** (`Ctrl+Espacio`, al escribir un punto o tras 2 letras):
  - columnas de las tablas usadas en la consulta, resolviendo alias (`c.` → columnas de `clientes c`);
  - tablas, vistas y esquemas; `dbo.` → tablas de ese esquema;
  - palabras clave SQL.
  - Los nombres se leen una vez por conexión; use *Consulta → Actualizar datos del autocompletado*
    tras crear tablas.
- **Historial de consultas** (`Ctrl+Shift+H`): fecha, conexión, estado y duración; búsqueda,
  vista previa e inserción en la consola. Se guardan las últimas 1000 en `~/.sql-studio-grid/history.tsv`.
- **Ver DDL** (clic derecho en tabla, vista o procedimiento):
  - Oracle: `DBMS_METADATA.GET_DDL`
  - MySQL: `SHOW CREATE ...`
  - SQL Server: `OBJECT_DEFINITION` (vistas y procedimientos)
  - Sybase: `sp_helptext` (vistas y procedimientos)
  - Tablas de SQL Server y Sybase: se arma desde los metadatos (columnas, nulos, defaults, llave primaria).
- **Formatear SQL** (`Ctrl+Shift+F`): sobre la selección o todo el script; mayúsculas en palabras
  clave, una cláusula por línea, subconsultas sangradas. Se puede deshacer con `Ctrl+Z`.

## Requisitos

- JDK 25
- Maven 3.9+

## Compilar y ejecutar

```bash
mvn clean package
java -jar target/sql-studio-grid-0.2.0.jar
```

En desarrollo:

```bash
mvn compile exec:java
```

## Drivers incluidos

| Motor | Driver | URL generada |
|---|---|---|
| SQL Server | mssql-jdbc | `jdbc:sqlserver://host:1433;databaseName=bd;encrypt=true;trustServerCertificate=true` |
| Sybase ASE | jTDS 1.3.1 | `jdbc:jtds:sybase://host:5000/bd` |
| Oracle | ojdbc11 | `jdbc:oracle:thin:@//host:1521/servicio` |
| MySQL | mysql-connector-j | `jdbc:mysql://host:3306/bd` |

**Sybase con jConnect:** jConnect no está en Maven Central. Si lo prefiere, instale el jar en su
repositorio local, agréguelo como dependencia y use una URL personalizada, por ejemplo
`jdbc:sybase:Tds:host:5000/bd`.

## Estructura

```
src/main/java/com/quasera/sqlstudiogrid/
├── App.java                  punto de entrada y tema
├── core/                     DbType, ConnectionProfile, ProfileStore, CredentialCipher
├── connectivity/             ConnectionFactory, QueryExecutor, SqlScriptSplitter, QueryResult
├── metadata/                 MetadataService, MetadataCache (autocompletado), DdlService
├── sql/                      SqlTokenizer, SqlFormatter, SqlCompletion, SqlKeywords
└── ui/                       MainFrame, ExplorerPanel, QueryConsolePanel, ConnectionDialog
    ├── editor/               SqlEditor (resaltado), LineNumberView, CompletionPopup
    └── grid/                 ResultPanel, ResultTableModel, CsvExporter
```

## Atajos

| Atajo | Acción |
|---|---|
| `Ctrl+Enter` / `F5` | Ejecutar selección o script |
| `Esc` | Cancelar ejecución |
| `Ctrl+T` | Nueva consola |
| `Ctrl+Shift+N` | Nueva conexión |
| `Ctrl+O` / `Ctrl+S` | Abrir / guardar script |
| `Ctrl+W` | Cerrar consola |
| `Ctrl+Shift+E` | Exportar resultado a CSV |
| `Ctrl+Z` / `Ctrl+Y` | Deshacer / rehacer |
| `Ctrl+Espacio` | Autocompletar |
| `Ctrl+Shift+F` | Formatear SQL |
| `Ctrl+Shift+H` | Historial de consultas |

## Hoja de ruta

- **Fase 3:** edición de datos en la grilla, comparación de esquemas, túneles SSH, diagramas ER.
