package com.hxm.java.ai.langchain4j.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 医生排班（号源）实体：某位医生在某科室、某日期、某时段的可预约号源。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("doctor_schedule")
public class DoctorSchedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String doctorName;   // 医生姓名
    private String department;   // 科室
    private String date;         // 日期，如 2026-08-14
    private String time;         // 时段：上午 / 下午
    private Integer totalSlots;  // 总号源数
    private Integer bookedSlots; // 已预约数
}
