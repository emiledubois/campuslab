ALTER TABLE booking RENAME COLUMN student_sub TO student_oid;
ALTER INDEX idx_booking_student_sub RENAME TO idx_booking_student_oid;
