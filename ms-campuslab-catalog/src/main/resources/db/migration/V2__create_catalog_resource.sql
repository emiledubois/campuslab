CREATE TABLE catalog_resource (
    id              UUID PRIMARY KEY,
    resource_type   VARCHAR(20) NOT NULL,
    name            VARCHAR(150) NOT NULL,
    description     VARCHAR(1000),
    location        VARCHAR(150),
    stock           INTEGER,
    cupo            INTEGER,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_catalog_resource_type
        CHECK (resource_type IN ('LABORATORIO', 'EQUIPO', 'INSUMO')),
    CONSTRAINT chk_catalog_resource_stock_cupo_shape CHECK (
        (resource_type = 'LABORATORIO' AND cupo IS NOT NULL AND stock IS NULL)
        OR
        (resource_type IN ('EQUIPO', 'INSUMO') AND stock IS NOT NULL AND cupo IS NULL)
    ),
    CONSTRAINT chk_catalog_resource_stock_nonnegative CHECK (stock IS NULL OR stock >= 0),
    CONSTRAINT chk_catalog_resource_cupo_nonnegative CHECK (cupo IS NULL OR cupo >= 0)
);

CREATE INDEX idx_catalog_resource_type ON catalog_resource (resource_type);
