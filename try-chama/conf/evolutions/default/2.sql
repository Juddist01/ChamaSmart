-- !Ups

CREATE TABLE loan_repayments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    loan_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    chama_id BIGINT NOT NULL,
    amount DECIMAL(15,2) NOT NULL,
    payment_reference VARCHAR(120) NOT NULL,
    status ENUM('pending', 'confirmed', 'rejected')
        NOT NULL DEFAULT 'pending',
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by BIGINT NULL,
    reviewed_at TIMESTAMP NULL,
    review_note VARCHAR(500) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_chama_payment_reference
        (chama_id, payment_reference),
    KEY idx_repayment_loan (loan_id),
    KEY idx_repayment_review (chama_id, status),

    CONSTRAINT fk_repayment_loan
        FOREIGN KEY (loan_id) REFERENCES loans(id),
    CONSTRAINT fk_repayment_user
        FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT fk_repayment_chama
        FOREIGN KEY (chama_id) REFERENCES chama(chama_id),
    CONSTRAINT fk_repayment_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES user(id),

    CONSTRAINT chk_repayment_amount CHECK (amount > 0)
) ENGINE=InnoDB;

-- !Downs

DROP TABLE loan_repayments;
