"""Host SQLite checks for the existing database upgrade and the generated trip DAO."""
import json
from pathlib import Path
import re
import sqlite3
import unittest

MODULE = Path(__file__).resolve().parents[1] / "app-mobile"
SCHEMAS = MODULE / "schemas/org.jrs82.fsclock.db.FsClockDb"


def entities(version):
    return json.loads((SCHEMAS / f"{version}.json").read_text(encoding="utf-8"))["database"]["entities"]


def create_table(db, entity):
    table = entity["tableName"]
    db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
    for index in entity["indices"]:
        db.execute(index["createSql"].replace("${TABLE_NAME}", table))


class TripMigrationTest(unittest.TestCase):
    def test_upgrade_and_backup_merge(self):
        old = entities(7)
        new = {entity["tableName"]: entity for entity in entities(8)}
        self.assertEqual({entity["tableName"] for entity in old} | {"trips"}, set(new))
        with sqlite3.connect(":memory:") as db, sqlite3.connect(":memory:") as fresh:
            before = {}
            for entity in old:
                table = entity["tableName"]
                self.assertEqual(entity, new[table])
                create_table(db, entity)
                fields = entity["fields"]
                for seed in (1, 2):
                    values = [f"row{seed}" if f["affinity"] == "TEXT" else seed for f in fields]
                    placeholders = ", ".join("?" for _ in values)
                    db.execute(f"INSERT INTO {table} VALUES ({placeholders})", values)
                before[table] = db.execute(f"SELECT * FROM {table} ORDER BY rowid").fetchall()

            # Execute every execSQL from the actual 7 -> 8 migration, including any destructive change.
            source = (MODULE / "core/java/org/jrs82/fsclock/db/FsClockDb.java").read_text(encoding="utf-8")
            block = source.split("new Migration(7, 8)", 1)[1].split("};", 1)[0]
            statements = re.findall(r"db\.execSQL\((.*?)\);", block, re.S)
            self.assertTrue(statements)
            for statement in statements:
                db.execute("".join(re.findall(r'"([^"]*)"', statement)))
            for table, rows in before.items():
                self.assertEqual(rows, db.execute(f"SELECT * FROM {table} ORDER BY rowid").fetchall())
            create_table(fresh, new["trips"])
            for pragma in ("table_info", "index_list", "foreign_key_list"):
                sql = f"PRAGMA {pragma}(trips)"
                self.assertEqual(fresh.execute(sql).fetchall(), db.execute(sql).fetchall())

            # Upgrade the populated version 8 database using the production statements.
            legacy_trip = ("legacy", 100, 110, 0, 7.0, 10, 5, 1.0)
            db.execute("INSERT INTO trips VALUES (?,?,?,?,?,?,?,?)", legacy_trip)
            block = source.split("new Migration(8, 9)", 1)[1].split("};", 1)[0]
            for statement in re.findall(r'db\.execSQL\("([^"]+)"\);', block):
                db.execute(statement)
            self.assertEqual(legacy_trip + (0, 0), db.execute("SELECT * FROM trips").fetchone())
            for table, rows in before.items():
                self.assertEqual(rows, db.execute(f"SELECT * FROM {table} ORDER BY rowid").fetchall())
            latest_schema = {entity["tableName"]: entity for entity in entities(9)}
            self.assertEqual({k: v for k, v in new.items() if k != "trips"},
                             {k: v for k, v in latest_schema.items() if k != "trips"})
            with sqlite3.connect(":memory:") as version9:
                create_table(version9, latest_schema["trips"])
                for pragma in ("table_info", "index_list", "foreign_key_list"):
                    sql = f"PRAGMA {pragma}(trips)"
                    self.assertEqual(version9.execute(sql).fetchall(), db.execute(sql).fetchall())
            db.execute("DELETE FROM trips WHERE id='legacy'")

            # Exercise the exact SQL generated for save, restore/undo and deletion.
            path = MODULE / "build/generated/ap_generated_sources/debug/out/org/jrs82/fsclock/db/TripDao_Impl.java"
            adapter = path.read_text(encoding="utf-8")
            save, merge = [re.search(r'return "(INSERT OR ' + conflict + r' [^"]+)"', adapter)[1]
                           for conflict in ("REPLACE", "IGNORE")]
            delete = re.search(r'"(DELETE FROM trips [^"]+)"', adapter)[1]
            first = ("a", 1000, 4000, 1, 3.25, 3000, 2000, 2.5, 1, 1)
            latest = ("a", 1000, 9000, 1, 7.75, 6000, 4000, 4.25, 1, 0)
            db.execute(save, first)
            db.execute(save, latest)
            db.execute(merge, first)
            self.assertEqual([latest], db.execute("SELECT * FROM trips").fetchall())
            db.execute(delete, ("a",))
            self.assertEqual([], db.execute("SELECT * FROM trips").fetchall())
            db.execute(merge, latest)
            db.execute(merge, latest)
            self.assertEqual([latest], db.execute("SELECT * FROM trips").fetchall())
            self.assertEqual("ok", db.execute("PRAGMA integrity_check").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
