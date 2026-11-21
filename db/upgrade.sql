USE chama_app;

-- 1. Ensure admin_id column exists on chama table
ALTER TABLE `chama` ADD COLUMN `admin_id` BIGINT DEFAULT NULL;

-- 2. Create chama_members join table if it doesn't already exist
CREATE TABLE IF NOT EXISTS `chama_members` (
    `chama_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    PRIMARY KEY (`chama_id`, `user_id`),
    CONSTRAINT `fk_cm_chama` FOREIGN KEY (`chama_id`) REFERENCES `chama` (`chama_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_cm_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
);
