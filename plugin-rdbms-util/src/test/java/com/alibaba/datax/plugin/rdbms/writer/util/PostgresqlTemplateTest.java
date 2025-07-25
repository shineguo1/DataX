package com.alibaba.datax.plugin.rdbms.writer.util;

import java.util.Arrays;
import java.util.stream.Collectors;

import static com.alibaba.datax.plugin.rdbms.writer.util.PostgresqlTemplate.onDuplicateKeyUpdateString;

public class PostgresqlTemplateTest {


    public static void main(String[] args) {
        System.out.println(onDuplicateKeyUpdateString("update#(member_id, app_code, app_biz_code, app_record_no)#set()", Arrays.asList()));
        System.out.println(onDuplicateKeyUpdateString("update#on(member_id, app_code, app_biz_code, app_record_no)#set(updated_by, updated_at)#where(main_export_region, secondary_export_region)",
                Arrays.stream("risk_rule_status, app_creation_time, app_update_time, main_export_region, secondary_export_region, goods_type, historical_annual_sales, updated_by, updated_at".replace(" ", "").split(",")).collect(Collectors.toList())));
        System.out.println(onDuplicateKeyUpdateString("update#on(member_id, app_code, app_biz_code, app_record_no)#set(updated_by, updated_at)#set_if_changed(risk_rule_status, name, age)#set_if_changed(app_status, country_code)#set_if_changed(update_col3)#where(main_export_region, secondary_export_region)",
                Arrays.stream("risk_rule_status, app_creation_time, app_update_time, main_export_region, secondary_export_region, goods_type, historical_annual_sales, updated_by, updated_at".replace(" ", "").split(",")).collect(Collectors.toList())));
    }

}