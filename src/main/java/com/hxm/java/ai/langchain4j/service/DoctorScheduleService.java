package com.hxm.java.ai.langchain4j.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hxm.java.ai.langchain4j.entity.DoctorSchedule;

import java.util.List;

public interface DoctorScheduleService extends IService<DoctorSchedule> {

    /**
     * 判断某科室、日期、时段是否有号源。
     * 若指定了医生，则判断该医生是否有未约满的排班；否则判断该科室该时段是否有未约满的排班。
     */
    boolean hasAvailableSlot(String department, String date, String time, String doctorName);

    /**
     * 查询可预约的排班列表。
     * date / time / doctorName 允许为 null 或空，为空表示不限定该条件。
     * 只返回未约满（booked_slots &lt; total_slots）的排班，按日期、时段升序。
     */
    List<DoctorSchedule> listAvailable(String department, String date, String time, String doctorName);

    /**
     * 预约成功后号源 -1。若未指定医生，则占用该科室该时段第一个未约满排班的号源。
     */
    void decrementSlot(String department, String date, String time, String doctorName);

    /**
     * 取消预约后号源 +1。
     */
    void incrementSlot(String department, String date, String time, String doctorName);
}
