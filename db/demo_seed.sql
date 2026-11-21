USE chama_app;

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE chama_members;
TRUNCATE TABLE loans;
TRUNCATE TABLE contributions;
TRUNCATE TABLE user;
TRUNCATE TABLE chama;
SET FOREIGN_KEY_CHECKS = 1;

-- Insert Demo Admin User
INSERT INTO user (id, fullname, phoneno, email, password, chama_name, role)
VALUES (1, 'John Doe', '254711000111', 'admin@chama.co.ke', 'Password123!', 'Harambee Chama', 'chama_admin');

-- Insert Demo Chama
INSERT INTO chama (chama_id, name, description, admin_id, monthly_contribution_amount, balance, total_savings)
VALUES (1, 'Harambee Chama', 'Nairobi Savings Group', 1, 5000.00, 150000.00, 150000.00);

-- Link Admin in chama_members
INSERT INTO chama_members (chama_id, user_id) VALUES (1, 1);
