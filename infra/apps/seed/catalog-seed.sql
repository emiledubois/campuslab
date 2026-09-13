-- Demo-readiness.md §4/Decision 2 - plain DML against catalog_resource only (matching
-- ms-campuslab-catalog/src/main/resources/db/migration/V2__create_catalog_resource.sql's
-- columns exactly), never a Flyway migration. Idempotent via ON CONFLICT (id) DO NOTHING
-- on these fixed, hardcoded seed UUIDs, not by Flyway's versioning - re-running this file
-- (via scripts/seed.sh) against an already-seeded database is always a safe no-op.
-- Run via scripts/seed.sh, which resolves the running catalog-db container id via
-- `docker compose ... ps -q catalog-db` (never a hardcoded container name - review
-- iteration 1, finding 2) and pipes this file into `psql -U "$CATALOG_DB_USER" -d
-- "$CATALOG_DB_NAME"` against it, after catalog itself reports healthy (its own Flyway
-- migrations must have already run).

INSERT INTO catalog_resource (id, resource_type, name, description, location, stock, cupo)
VALUES
    ('a0000000-0000-4000-8000-000000000001', 'LABORATORIO', 'Laboratorio de Redes',
     'Laboratorio equipado con switches/routers para practicas de redes.', 'Edificio A, piso 2', NULL, 20),
    ('a0000000-0000-4000-8000-000000000002', 'LABORATORIO', 'Laboratorio de Fisica',
     'Laboratorio de fisica experimental con bancos opticos.', 'Edificio B, piso 1', NULL, 15),
    ('a0000000-0000-4000-8000-000000000003', 'LABORATORIO', 'Sala de Computo General',
     'Sala de computo de uso general para clases y practicas.', 'Edificio C, piso 3', NULL, 30),
    ('a0000000-0000-4000-8000-000000000004', 'EQUIPO', 'Osciloscopio Digital Tektronix',
     'Osciloscopio digital de 4 canales, 200 MHz.', 'Edificio A, piso 2', 6, NULL),
    ('a0000000-0000-4000-8000-000000000005', 'EQUIPO', 'Microscopio Optico',
     'Microscopio optico binocular de laboratorio.', 'Edificio B, piso 1', 10, NULL),
    ('a0000000-0000-4000-8000-000000000006', 'EQUIPO', 'Impresora 3D',
     'Impresora 3D FDM para prototipado rapido.', 'Edificio C, piso 3', 3, NULL),
    ('a0000000-0000-4000-8000-000000000007', 'INSUMO', 'Kit de Resistencias',
     'Kit surtido de resistencias para practicas de electronica.', 'Edificio A, piso 2', 100, NULL),
    ('a0000000-0000-4000-8000-000000000008', 'INSUMO', 'Guantes de Nitrilo (caja)',
     'Caja de guantes de nitrilo, talla M, uso en laboratorio quimico.', 'Edificio B, piso 1', 50, NULL)
ON CONFLICT (id) DO NOTHING;
