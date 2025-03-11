package com.alibaba.datax.plugin.rdbms.writer.util;

import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.rdbms.util.DBUtil;
import com.alibaba.datax.plugin.rdbms.util.DBUtilErrorCode;
import com.alibaba.datax.plugin.rdbms.util.DataBaseType;
import com.alibaba.datax.plugin.rdbms.util.RdbmsException;
import com.alibaba.datax.plugin.rdbms.writer.Constant;
import com.alibaba.datax.plugin.rdbms.writer.Key;
import com.alibaba.druid.sql.parser.ParserException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class WriterUtil {
    private static final Logger LOG = LoggerFactory.getLogger(WriterUtil.class);

    //TODO 切分报错
    public static List<Configuration> doSplit(Configuration simplifiedConf,
                                              int adviceNumber) {

        List<Configuration> splitResultConfigs = new ArrayList<Configuration>();

        int tableNumber = simplifiedConf.getInt(Constant.TABLE_NUMBER_MARK);

        //处理单表的情况
        if (tableNumber == 1) {
            //由于在之前的  master prepare 中已经把 table,jdbcUrl 提取出来，所以这里处理十分简单
            for (int j = 0; j < adviceNumber; j++) {
                splitResultConfigs.add(simplifiedConf.clone());
            }

            return splitResultConfigs;
        }

        if (tableNumber != adviceNumber) {
            throw DataXException.asDataXException(DBUtilErrorCode.CONF_ERROR,
                    String.format("您的配置文件中的列配置信息有误. 您要写入的目的端的表个数是:%s , 但是根据系统建议需要切分的份数是：%s. 请检查您的配置并作出修改.",
                            tableNumber, adviceNumber));
        }

        String jdbcUrl;
        List<String> preSqls = simplifiedConf.getList(Key.PRE_SQL, String.class);
        List<String> postSqls = simplifiedConf.getList(Key.POST_SQL, String.class);

        List<Object> conns = simplifiedConf.getList(Constant.CONN_MARK,
                Object.class);

        for (Object conn : conns) {
            Configuration sliceConfig = simplifiedConf.clone();

            Configuration connConf = Configuration.from(conn.toString());
            jdbcUrl = connConf.getString(Key.JDBC_URL);
            sliceConfig.set(Key.JDBC_URL, jdbcUrl);

            sliceConfig.remove(Constant.CONN_MARK);

            List<String> tables = connConf.getList(Key.TABLE, String.class);

            for (String table : tables) {
                Configuration tempSlice = sliceConfig.clone();
                tempSlice.set(Key.TABLE, table);
                tempSlice.set(Key.PRE_SQL, renderPreOrPostSqls(preSqls, table));
                tempSlice.set(Key.POST_SQL, renderPreOrPostSqls(postSqls, table));

                splitResultConfigs.add(tempSlice);
            }

        }

        return splitResultConfigs;
    }

    public static List<String> renderPreOrPostSqls(List<String> preOrPostSqls, String tableName) {
        if (null == preOrPostSqls) {
            return Collections.emptyList();
        }

        List<String> renderedSqls = new ArrayList<String>();
        for (String sql : preOrPostSqls) {
            //preSql为空时，不加入执行队列
            if (StringUtils.isNotBlank(sql)) {
                renderedSqls.add(sql.replace(Constant.TABLE_NAME_PLACEHOLDER, tableName));
            }
        }

        return renderedSqls;
    }

    public static void executeSqls(Connection conn, List<String> sqls, String basicMessage, DataBaseType dataBaseType) {
        Statement stmt = null;
        String currentSql = null;
        try {
            stmt = conn.createStatement();
            for (String sql : sqls) {
                currentSql = sql;
                DBUtil.executeSqlWithoutResultSet(stmt, sql);
            }
        } catch (Exception e) {
            throw RdbmsException.asQueryException(dataBaseType, e, currentSql, null, null);
        } finally {
            DBUtil.closeDBResources(null, stmt, null);
        }
    }

    public static void main(String[] args) {
        System.out.println(getWriteTemplate(
                Arrays.asList("id", "member_id", "app_code", "app_biz_code", "app_record_no", "app_org_record_no", "monitor_rule_status", "app_creation_time", "app_update_time", "sub_biz_type", "main_export_region", "secondary_export_region", "goods_type", "historical_annual_sales", "created_at", "created_by", "updated_at", "updated_by"),
                Arrays.asList("member_id", "app_code", "app_biz_code", "app_record_no", "app_org_record_no"),
                "update#on(member_id, app_code, app_biz_code, app_record_no)#set(app_org_record_no, sub_biz_type, app_update_time, monitor_rule_status, updated_by)#where(app_org_record_no, sub_biz_type)",
                DataBaseType.PostgreSQL,
                false
        ));
    }

    public static String getWriteTemplate(List<String> columnHolders, List<String> valueHolders, String writeMode, DataBaseType dataBaseType, boolean forceUseUpdate) {
        boolean isWriteModeLegal = writeMode.trim().toLowerCase().startsWith("insert")
                || writeMode.trim().toLowerCase().startsWith("replace")
                || writeMode.trim().toLowerCase().startsWith("update");

        if (!isWriteModeLegal) {
            throw DataXException.asDataXException(DBUtilErrorCode.ILLEGAL_VALUE,
                    String.format("您所配置的 writeMode:%s 错误. 因为DataX 目前仅支持replace,update 或 insert 方式. 请检查您的配置并作出修改.", writeMode));
        }
        // && writeMode.trim().toLowerCase().startsWith("replace")
        String writeDataSqlTemplate;
        if (forceUseUpdate ||
                ((dataBaseType == DataBaseType.MySql || dataBaseType == DataBaseType.Tddl) && writeMode.trim().toLowerCase().startsWith("update"))
        ) {
            //update只在mysql下使用

            writeDataSqlTemplate = new StringBuilder()
                    .append("INSERT INTO %s (").append(StringUtils.join(columnHolders, ","))
                    .append(") VALUES(").append(StringUtils.join(valueHolders, ","))
                    .append(")")
                    .append(onDuplicateKeyUpdateString(columnHolders))
                    .toString();
        } else if (dataBaseType == DataBaseType.PostgreSQL && writeMode.trim().toLowerCase().startsWith("update")) {
            //新增postgreSQL的更新模式，进行增量更新
            writeDataSqlTemplate = new StringBuilder()
                    .append("INSERT INTO %s as t0 (").append(StringUtils.join(columnHolders, ","))
                    .append(") VALUES(").append(StringUtils.join(valueHolders, ","))
                    .append(")")
                    .append(onDuplicateKeyUpdateStringForPostgresql(writeMode.trim(), columnHolders))
                    .toString();
        } else {

            //这里是保护,如果其他错误的使用了update,需要更换为replace
            if (writeMode.trim().toLowerCase().startsWith("update")) {
                writeMode = "replace";
            }
            writeDataSqlTemplate = new StringBuilder().append(writeMode)
                    .append(" INTO %s (").append(StringUtils.join(columnHolders, ","))
                    .append(") VALUES(").append(StringUtils.join(valueHolders, ","))
                    .append(")").toString();
        }

        return writeDataSqlTemplate;
    }

    public static String onDuplicateKeyUpdateString(List<String> columnHolders) {
        if (columnHolders == null || columnHolders.size() < 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(" ON DUPLICATE KEY UPDATE ");
        boolean first = true;
        for (String column : columnHolders) {
            if (!first) {
                sb.append(",");
            } else {
                first = false;
            }
            sb.append(column);
            sb.append("=VALUES(");
            sb.append(column);
            sb.append(")");
        }

        return sb.toString();
    }

    private static String onDuplicateKeyUpdateStringForPostgresql(String writeMode, List<String> columnHolders) {
        String[] writeModeArr = writeMode.split("#", -1);
        int writeModeArrLen = writeModeArr.length;
        writeMode = writeModeArr[0].replace(" ", "");

        StringBuilder sb;
        if ("update".equals(writeMode) && writeModeArrLen == 2) {
            sb = new StringBuilder().append(" ON CONFLICT ").append(writeModeArr[1].replace(" ", "")).append(" DO NOTHING");
        } else if ("update".equals(writeMode) && writeModeArrLen >= 3) {
            if (writeModeArr[1].startsWith("(")) {
                sb = upsertOldVersionForPostgresql(columnHolders, writeModeArr);
            } else {
                sb = upsertNewVersionForPostgresql(columnHolders, writeModeArr);
            }
        } else {
            throw DataXException.asDataXException(DBUtilErrorCode.ILLEGAL_VALUE,
                    String.format("您所配置的 writeMode(postgresql):%s 错误. " +
                            "语法为update#on(col1, col2, ...)#set(col1, col2, ...)[#where(col1, col2, ...) | #where_sql(your_sql)]请检查您的配置并作出修改.", writeMode));

        }
        return sb.toString();
    }

    private static StringBuilder upsertNewVersionForPostgresql(List<String> columnHolders, String[] writeModeArr) {
        StringBuilder onConflict = new StringBuilder();
        List<String> updateSqlList = new ArrayList<>();
        List<String> whereSqlList = new ArrayList<>();
        StringBuilder doUpdateSet = new StringBuilder();
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
                String[] updateFieldArr = content.replace(" ", "").replace("(", "").replace(")", "").split(",", -1);
                for (String updateField : updateFieldArr) {
                    if (!columnHolders.contains(updateField)) {
                        continue;
                    }
                    updateSqlList.add(updateField + "=EXCLUDED." + updateField);
                }
            } else if ("where_sql".equalsIgnoreCase(op)) {
                //where子句
                where.append(" WHERE ").append(content);
            } else if ("where".equalsIgnoreCase(op)) {
                String[] whereFieldArr = content.replace(" ", "").replace("(", "").replace(")", "").split(",", -1);

                for (String whereField : whereFieldArr) {
                    if (!columnHolders.contains(whereField)) {
                        continue;
                    }
                    whereSqlList.add("(t0." + whereField + " is not null and (t0." + whereField + " != EXCLUDED." + whereField + " or EXCLUDED." + whereField + " is null))");
                }
                if (whereFieldArr.length > 0) {
                    where.append(" WHERE ").append(StringUtils.join(whereSqlList, " OR "));
                }
            } else {
                throw DataXException.asDataXException(DBUtilErrorCode.ILLEGAL_VALUE,
                        "您所配置的 writeMode(postgresql) 错误. " +
                                "语法为update#on(col1, col2, ...)#set(col1, col2, ...)[#where(col1, col2, ...)|#where_sql(your_sql)]请检查您的配置并作出修改.");
            }
        }
        if (updateSqlList.isEmpty()) {
            doUpdateSet.append(" DO NOTHING");
        } else {
            doUpdateSet.append(" DO UPDATE SET ").append(StringUtils.join(updateSqlList, ","));
        }
        return onConflict.append(doUpdateSet).append(where);
    }

    private static StringBuilder upsertOldVersionForPostgresql(List<String> columnHolders, String[] writeModeArr) {
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

    public static void preCheckPrePareSQL(Configuration originalConfig, DataBaseType type) {
        List<Object> conns = originalConfig.getList(Constant.CONN_MARK, Object.class);
        Configuration connConf = Configuration.from(conns.get(0).toString());
        String table = connConf.getList(Key.TABLE, String.class).get(0);

        List<String> preSqls = originalConfig.getList(Key.PRE_SQL,
                String.class);
        List<String> renderedPreSqls = WriterUtil.renderPreOrPostSqls(
                preSqls, table);

        if (null != renderedPreSqls && !renderedPreSqls.isEmpty()) {
            LOG.info("Begin to preCheck preSqls:[{}].",
                    StringUtils.join(renderedPreSqls, ";"));
            for (String sql : renderedPreSqls) {
                try {
                    DBUtil.sqlValid(sql, type);
                } catch (ParserException e) {
                    throw RdbmsException.asPreSQLParserException(type, e, sql);
                }
            }
        }
    }

    public static void preCheckPostSQL(Configuration originalConfig, DataBaseType type) {
        List<Object> conns = originalConfig.getList(Constant.CONN_MARK, Object.class);
        Configuration connConf = Configuration.from(conns.get(0).toString());
        String table = connConf.getList(Key.TABLE, String.class).get(0);

        List<String> postSqls = originalConfig.getList(Key.POST_SQL,
                String.class);
        List<String> renderedPostSqls = WriterUtil.renderPreOrPostSqls(
                postSqls, table);
        if (null != renderedPostSqls && !renderedPostSqls.isEmpty()) {

            LOG.info("Begin to preCheck postSqls:[{}].",
                    StringUtils.join(renderedPostSqls, ";"));
            for (String sql : renderedPostSqls) {
                try {
                    DBUtil.sqlValid(sql, type);
                } catch (ParserException e) {
                    throw RdbmsException.asPostSQLParserException(type, e, sql);
                }

            }
        }
    }


}
