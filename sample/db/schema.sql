CREATE TABLE `test_user` (
                             `id` BIGINT NOT NULL AUTO_INCREMENT,
                             `name` VARCHAR(64) NOT NULL,
                             `age` INT,
                             `is_active` TINYINT(1) NOT NULL DEFAULT 1,
                             PRIMARY KEY (`id`)
);
