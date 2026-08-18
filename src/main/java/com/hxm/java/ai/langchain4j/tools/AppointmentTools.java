package com.hxm.java.ai.langchain4j.tools;

import com.hxm.java.ai.langchain4j.entity.Appointment;
import com.hxm.java.ai.langchain4j.entity.DoctorSchedule;
import com.hxm.java.ai.langchain4j.service.AppointmentService;
import com.hxm.java.ai.langchain4j.service.DoctorScheduleService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AppointmentTools {

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private DoctorScheduleService doctorScheduleService;

    @Tool(name = "bookAppointment",
            value = "预约挂号：根据参数，先执行工具方法queryDepartment查询是否可预约，并直接给用户回答是否可预约，并让用户确认所有预约信息（姓名、身份证号、科室、日期、时段、医生），用户确认后再进行预约。如果用户没有提供具体的医生姓名，先调用queryDepartment查询该科室可预约的医生列表，让用户从中选择一位。")
    public String bookAppointment(Appointment appointment) {
        // 1. 查找数据库中是否包含对应的预约记录（防重复预约）
        Appointment appointmentDB = appointmentService.getOne(appointment);
        if (appointmentDB != null) {
            return "您在相同的科室和时间已有预约";
        }
        // 2. 真实校验号源
        if (!doctorScheduleService.hasAvailableSlot(appointment.getDepartment(),
                appointment.getDate(), appointment.getTime(), appointment.getDoctorName())) {
            return "该科室该时段暂无号源，请选择其他时间或其他医生";
        }
        // 3. 保存预约记录
        appointment.setId(null); // 防止大模型幻觉设置了id
        if (!appointmentService.save(appointment)) {
            return "预约失败";
        }
        // 4. 号源 -1
        doctorScheduleService.decrementSlot(appointment.getDepartment(),
                appointment.getDate(), appointment.getTime(), appointment.getDoctorName());
        return "预约成功，并返回预约详情";
    }

    @Tool(name = "cancelAppointment",
            value = "取消预约挂号：根据参数，查询预约是否存在，如果存在则删除预约记录并返回取消预约成功，否则返回取消预约失败")
    public String cancelAppointment(Appointment appointment) {
        Appointment appointmentDB = appointmentService.getOne(appointment);
        if (appointmentDB == null) {
            return "您没有预约记录，请核对预约科室和时间";
        }
        if (!appointmentService.removeById(appointmentDB.getId())) {
            return "取消预约失败";
        }
        // 号源 +1
        doctorScheduleService.incrementSlot(appointmentDB.getDepartment(),
                appointmentDB.getDate(), appointmentDB.getTime(), appointmentDB.getDoctorName());
        return "取消预约成功";
    }

    @Tool(name = "queryDepartment",
            value = "查询号源：根据科室名称、日期、时间、医生查询可预约的号源。日期和时间若用户未明确提供可不传，此时返回该科室/医生所有可预约的日期时段。返回结果包含具体的医生、日期、时段和剩余号源数。注意：可预约医生仅以本工具返回结果为准，严禁使用知识库/文档中出现的医生姓名来推荐或预约。")
    public String queryDepartment(
            @P(value = "科室名称") String name,
            @P(value = "日期，格式YYYY-MM-DD，用户未提供时可不传", required = false) String date,
            @P(value = "时间，可选值：上午、下午，用户未提供时可不传", required = false) String time,
            @P(value = "医生名称", required = false) String doctorName) {
        System.out.println("查询号源");
        System.out.println("科室名称：" + name);
        System.out.println("日期：" + date);
        System.out.println("时间：" + time);
        System.out.println("医生名称：" + doctorName);

        // 真实查询排班表（只返回未约满的排班）。
        boolean hasDoctor = doctorName != null && !doctorName.isBlank();
        List<DoctorSchedule> list = doctorScheduleService.listAvailable(name, date, time, doctorName);
        if (list.isEmpty()) {
            // 指定了医生却无号：去掉医生限制，查同科室同时段其他可预约医生，
            // 直接把真实名单交给大模型，避免它去知识库编造医生姓名。
            if (hasDoctor) {
                List<DoctorSchedule> alternatives = doctorScheduleService.listAvailable(name, date, time, null);
                if (!alternatives.isEmpty()) {
                    StringBuilder sb = new StringBuilder("医生 ").append(doctorName)
                            .append(" 当前无号源。该条件下其他可预约医生如下：\n");
                    appendSchedules(sb, alternatives);
                    return sb.toString();
                }
            }
            return "未查询到可预约号源。科室：" + name
                    + (hasDoctor ? "，医生：" + doctorName : "")
                    + (date == null || date.isBlank() ? "" : "，日期：" + date)
                    + (time == null || time.isBlank() ? "" : "，时间：" + time);
        }
        StringBuilder sb = new StringBuilder("可预约号源如下：\n");
        appendSchedules(sb, list);
        return sb.toString();
    }

    private void appendSchedules(StringBuilder sb, List<DoctorSchedule> list) {
        for (DoctorSchedule s : list) {
            sb.append("- ").append(s.getDoctorName())
                    .append(" 医生，").append(s.getDate())
                    .append(" ").append(s.getTime())
                    .append("，剩余号源 ").append(s.getTotalSlots() - s.getBookedSlots())
                    .append(" 个\n");
        }
    }
}
