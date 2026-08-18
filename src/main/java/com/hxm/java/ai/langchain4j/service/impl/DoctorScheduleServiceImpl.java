package com.hxm.java.ai.langchain4j.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hxm.java.ai.langchain4j.entity.DoctorSchedule;
import com.hxm.java.ai.langchain4j.mapper.DoctorScheduleMapper;
import com.hxm.java.ai.langchain4j.service.DoctorScheduleService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DoctorScheduleServiceImpl
        extends ServiceImpl<DoctorScheduleMapper, DoctorSchedule>
        implements DoctorScheduleService {

    @Override
    public boolean hasAvailableSlot(String department, String date, String time, String doctorName) {
        LambdaQueryWrapper<DoctorSchedule> wrapper = buildUnbookedWrapper(department, date, time);
        if (doctorName != null && !doctorName.isBlank()) {
            wrapper.eq(DoctorSchedule::getDoctorName, doctorName);
        }
        return baseMapper.selectCount(wrapper) > 0;
    }

    @Override
    public List<DoctorSchedule> listAvailable(String department, String date, String time, String doctorName) {
        LambdaQueryWrapper<DoctorSchedule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DoctorSchedule::getDepartment, department)
                .apply("booked_slots < total_slots");
        if (date != null && !date.isBlank()) {
            wrapper.eq(DoctorSchedule::getDate, date);
        }
        if (time != null && !time.isBlank()) {
            wrapper.eq(DoctorSchedule::getTime, time);
        }
        if (doctorName != null && !doctorName.isBlank()) {
            wrapper.eq(DoctorSchedule::getDoctorName, doctorName);
        }
        wrapper.orderByAsc(DoctorSchedule::getDate)
                .orderByAsc(DoctorSchedule::getTime);
        return baseMapper.selectList(wrapper);
    }

    @Override
    public void decrementSlot(String department, String date, String time, String doctorName) {
        DoctorSchedule target = selectTarget(department, date, time, doctorName, true);
        if (target != null) {
            UpdateWrapper<DoctorSchedule> update = new UpdateWrapper<>();
            update.eq("id", target.getId()).setSql("booked_slots = booked_slots + 1");
            baseMapper.update(null, update);
        }
    }

    @Override
    public void incrementSlot(String department, String date, String time, String doctorName) {
        // 取消时可能该时段已约满，因此不要求未约满，只按医生（或首个排班）定位
        DoctorSchedule target = selectTarget(department, date, time, doctorName, false);
        if (target != null) {
            UpdateWrapper<DoctorSchedule> update = new UpdateWrapper<>();
            update.eq("id", target.getId())
                    .setSql("booked_slots = GREATEST(booked_slots - 1, 0)"); // 防止减成负数
            baseMapper.update(null, update);
        }
    }

    /**
     * 构建"未约满"的查询条件（booked_slots < total_slots 为列与列比较，需用 apply）。
     */
    private LambdaQueryWrapper<DoctorSchedule> buildUnbookedWrapper(String department, String date, String time) {
        LambdaQueryWrapper<DoctorSchedule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DoctorSchedule::getDepartment, department)
                .eq(DoctorSchedule::getDate, date)
                .eq(DoctorSchedule::getTime, time)
                .apply("booked_slots < total_slots");
        return wrapper;
    }

    /**
     * 定位目标排班：指定医生则按其排班，否则取该科室该时段第一个（未约满的）排班。
     *
     * @param requireUnbooked 是否只取未约满的排班（减号源时为 true，加号源时为 false）
     */
    private DoctorSchedule selectTarget(String department, String date, String time,
                                        String doctorName, boolean requireUnbooked) {
        LambdaQueryWrapper<DoctorSchedule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DoctorSchedule::getDepartment, department)
                .eq(DoctorSchedule::getDate, date)
                .eq(DoctorSchedule::getTime, time);
        if (requireUnbooked) {
            wrapper.apply("booked_slots < total_slots");
        }
        if (doctorName != null && !doctorName.isBlank()) {
            wrapper.eq(DoctorSchedule::getDoctorName, doctorName);
        }
        wrapper.orderByAsc(DoctorSchedule::getId)
                .last("LIMIT 1");
        return baseMapper.selectOne(wrapper);
    }
}
