package com.alibaba.datax.plugin.rdbms.writer.util;

import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.plugin.rdbms.util.DBUtilErrorCode;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * @author dev
 * @version 1.0.0 createTime:  2025/3/13 11:51
 */
public class PostgresqlTemplate {

    public static final String TABLE_ALIAS = "t0";


    public static String onDuplicateKeyUpdateString(String writeMode, List<String> columnHolders) {
        String[] writeModeArr = writeMode.split("#", -1);
        int writeModeArrLen = writeModeArr.length;
        writeMode = writeModeArr[0].replace(" ", "");

        StringBuilder sb;
        if ("update".equals(writeMode) && writeModeArrLen == 2) {
            sb = new StringBuilder().append(" ON CONFLICT ").append(writeModeArr[1].replace(" ", "")).append(" DO NOTHING");
        } else if ("update".equals(writeMode) && writeModeArrLen >= 3) {
            if (writeModeArr[1].startsWith("(")) {
                sb = PostgresqlTemplate.upsertOldVersion(columnHolders, writeModeArr);
            } else {
                sb = PostgresqlTemplate.upsertNewVersion(columnHolders, writeModeArr);
            }
        } else {
            throw DataXException.asDataXException(DBUtilErrorCode.ILLEGAL_VALUE,
                    String.format("您所配置的 writeMode(postgresql):%s 错误. " +
                            "语法为update#on(col1, col2, ...)#set(col1, col2, ...)[#where(col1, col2, ...) | #where_sql(your_sql)]请检查您的配置并作出修改.", writeMode));

        }
        return sb.toString();
    }

    public static StringBuilder upsertNewVersion(List<String> columnHolders, String[] writeModeArr) {
        StringBuilder onConflict = new StringBuilder();
        List<String> updateSqlList = new ArrayList<>();
        List<String> whereSqlList = new ArrayList<>();
        StringBuilder where = new StringBuilder();
        //i=0是"update", 内容从i=1开始
        for (int i = 1; i < writeModeArr.length; i++) {
            String writeModSubString = writeModeArr[i];
            int idx = writeModSubString.indexOf("(");
            String op = writeModSubString.substring(0, idx).replace(" ", "");
            String content = writeModSubString.substring(idx);
            if ("on".equalsIgnoreCase(op)) {
                onConflict.append(" ON CONFLICT ").append(content.replace(" ", ""));
            } else if ("set".equalsIgnoreCase(op)) {
                // #set(col1, col2, col3,...)
                String[] updateFieldArr = splitColumns(content);
                for (String updateField : updateFieldArr) {
                    if (!columnHolders.contains(updateField)) {
                        continue;
                    }
                    updateSqlList.add(updateField + "=EXCLUDED." + updateField);
                }
            } else if("set_if_changed".equalsIgnoreCase(op)){
                // 当...condition_cols中有列发生改变时更新update_col
                // #set_if_changed(update_col, condition_col1, condition_col2, condition_col3,... )
                String[] fieldArr = splitColumns(content);
                if(fieldArr.length == 0){
                    continue;
                } else if(fieldArr.length == 1){
                    // 无条件column，等同于#set()
                    String updateField = fieldArr[0];
                    if (!columnHolders.contains(updateField)) {
                        continue;
                    }
                    updateSqlList.add(updateField + "=EXCLUDED." + updateField);
                } else {
                    // means: fieldArr.length >= 1
                    // target sql: (set) updateField = case when (if field1 changed) or (if field2 changed) or ... then EXCLUDED.updateField else TABLE_ALIAS.updateField END
                    String updateField = fieldArr[0];
                    List<String> conditions = new ArrayList<>();
                    for (int conditionalIndex = 1; conditionalIndex < fieldArr.length; conditionalIndex++) {
                        String conditionalField = fieldArr[conditionalIndex];
                        conditions.add(conditionSqlColumnChangedNotNull(conditionalField));
                    }
                    String updateSql = updateField + "= case when " + StringUtils.join(conditions, " OR ") + " then EXCLUDED." + updateField + " else " + TABLE_ALIAS + "." + updateField +" END";
                    updateSqlList.add(updateSql);
                }

            } else if ("where_sql".equalsIgnoreCase(op)) {
                //where子句 #where(...sql)
                where.append(content);
            } else if ("where".equalsIgnoreCase(op)) {
                // #where(col1, col2, col3,...)
                String[] whereFieldArr = splitColumns(content);

                for (String whereField : whereFieldArr) {
                    if (!columnHolders.contains(whereField)) {
                        continue;
                    }
                    whereSqlList.add(conditionSqlColumnChangedNotNull(whereField));
                }
                if (whereFieldArr.length > 0) {
                    where.append(StringUtils.join(whereSqlList, " OR "));
                }
            } else {
                throw DataXException.asDataXException(DBUtilErrorCode.ILLEGAL_VALUE,
                        "您所配置的 writeMode(postgresql) 错误. " +
                                "语法为update#on(col1, col2, ...)[#set(col1, col2, ...)][#set_if_changed(update_col, condition_col1, condition_col2,...)][#where(col1, col2, ...)][#where_sql(your_sql)]请检查您的配置并作出修改.");
            }
        }
        StringBuilder doUpdateSet = new StringBuilder();
        if (updateSqlList.isEmpty()) {
            doUpdateSet.append(" DO NOTHING");
        } else {
            doUpdateSet.append(" DO UPDATE SET ").append(StringUtils.join(updateSqlList, ","));
        }
        if (StringUtils.isNotEmpty(where)) {
            where.insert(0, " WHERE ");
        }
        return onConflict.append(doUpdateSet).append(where);
    }

    private static String[] splitColumns(String content) {
        return content.replace(" ", "").replace("(", "").replace(")", "").split(",", -1);
    }

    /**
     * 判断列是否发生改变，且不为null
     *
     * @param whereField 列名
     * @return
     */
    private static String conditionSqlColumnChangedNotNull(String whereField) {
        return "(EXCLUDED." + whereField + " is not null and (" + TABLE_ALIAS + "." + whereField + " != EXCLUDED." + whereField + " or " + TABLE_ALIAS + "." + whereField + " is null))";
    }

    public static StringBuilder upsertOldVersion(List<String> columnHolders, String[] writeModeArr) {
        StringBuilder sb = new StringBuilder();
        sb.append(" ON CONFLICT ").append(writeModeArr[1].replace(" ", ""));
        String[] updateFieldArr = writeModeArr[2].replace(" ", "").replace("(", "").replace(")", "").split(",", -1);

        List<String> updateSqlList = new ArrayList<>();
        for (String updateField : updateFieldArr) {
            if (!columnHolders.contains(updateField)) {
                continue;
            }
            updateSqlList.add(updateField + "=EXCLUDED." + updateField);
        }

        if (updateSqlList.isEmpty()) {
            sb.append(" DO NOTHING");
        } else {
            sb.append(" DO UPDATE SET ").append(StringUtils.join(updateSqlList, ","));
        }
        return sb;
    }
}
