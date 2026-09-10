package cl.campuslab.catalog.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StockDecrementLedgerRepository extends JpaRepository<StockDecrementLedger, StockDecrementLedgerKey> {
}
