-- 医生排班（号源）表
-- 在 MySQL（guiguxiaozhi 库）中执行

CREATE TABLE IF NOT EXISTS doctor_schedule (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    doctor_name  VARCHAR(50) NOT NULL COMMENT '医生姓名',
    department   VARCHAR(50) NOT NULL COMMENT '科室',
    date         VARCHAR(20) NOT NULL COMMENT '日期，如 2026-08-14',
    time         VARCHAR(10) NOT NULL COMMENT '时段：上午/下午',
    total_slots  INT DEFAULT 0 COMMENT '总号源数',
    booked_slots INT DEFAULT 0 COMMENT '已预约数'
) COMMENT '医生排班（号源）表';

-- 测试排班数据（覆盖 08-14 ~ 08-16，含"已约满"场景用于验证无号源分支）
INSERT INTO doctor_schedule (doctor_name, department, date, time, total_slots, booked_slots) VALUES
('张伟', '神经内科', '2026-08-14', '上午', 10, 3),
('李娜', '神经内科', '2026-08-14', '上午', 10, 10),   -- 已约满
('王强', '神经内科', '2026-08-14', '下午', 8, 0),
('赵敏', '心血管内科', '2026-08-14', '上午', 12, 5),
('陈静', '儿科', '2026-08-14', '下午', 6, 6),        -- 已约满
('刘洋', '神经内科', '2026-08-15', '上午', 10, 0),
('孙丽', '神经内科', '2026-08-15', '下午', 8, 2),
('周杰', '神经内科', '2026-08-16', '上午', 10, 1);
