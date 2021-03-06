package com.fibo.ddp.common.service.datax.runner.impl;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.fibo.ddp.common.dao.datax.datasource.SimpleMapper;
import com.fibo.ddp.common.model.common.enums.ErrorCodeEnum;
import com.fibo.ddp.common.model.datax.datainterface.InterfaceInfo;
import com.fibo.ddp.common.model.datax.datamanage.Field;
import com.fibo.ddp.common.model.datax.datamanage.FieldCallLog;
import com.fibo.ddp.common.model.datax.datamanage.FieldCond;
import com.fibo.ddp.common.model.datax.datasource.DataSource;
import com.fibo.ddp.common.service.common.runner.RunnerSessionManager;
import com.fibo.ddp.common.service.common.runner.SessionData;
import com.fibo.ddp.common.service.datax.datainterface.InterfaceService;
import com.fibo.ddp.common.service.datax.datamanage.FieldCallLogService;
import com.fibo.ddp.common.service.datax.datamanage.FieldService;
import com.fibo.ddp.common.service.datax.datasource.DataSourceService;
import com.fibo.ddp.common.service.datax.runner.CommonService;
import com.fibo.ddp.common.service.datax.runner.DataBaseReSource;
import com.fibo.ddp.common.service.datax.runner.ExecuteUtils;
import com.fibo.ddp.common.service.datax.runner.FieldTypeConsts;
import com.fibo.ddp.common.service.datax.runner.mysql.DataSourceContextHolder;
import com.fibo.ddp.common.service.datax.runner.mysql.DynamicDataSource;
import com.fibo.ddp.common.service.datax.runner.redis.RedisKSessionPool;
import com.fibo.ddp.common.utils.common.MD5;
import com.fibo.ddp.common.utils.constant.CommonConst;
import com.fibo.ddp.common.utils.exception.ApiException;
import com.fibo.ddp.common.utils.util.StringUtil;
import com.fibo.ddp.common.utils.util.runner.DictVariableUtils;
import com.fibo.ddp.common.utils.util.runner.jeval.EvaluationException;
import com.fibo.ddp.common.utils.util.runner.jeval.Evaluator;
import com.fibo.ddp.common.utils.util.runner.jeval.function.math.Groovy;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CommonServiceImpl implements CommonService {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Resource
    private SimpleMapper simpleMapper;

    @Autowired
    public FieldService fieldService;

    @Autowired
    private Groovy groovy;

    @Autowired
    private InterfaceService interfaceService;

    @Autowired
    private DataSourceService dataSourceService;
    @Autowired
    private RedisKSessionPool redisKSessionPool;
    @Autowired
    private FieldCallLogService fieldCallLogService;

    @Override
    public boolean getFieldByIds(List<Long> ids, Map<String, Object> inputParam) {
        if (ids == null || ids.size() == 0) {
            return true;
        }
        SessionData sessionData = RunnerSessionManager.getSession();
        Long organId = sessionData.getOrganId();
        List<Field> fieldList = fieldService.findFieldByIdsbyorganId(organId, ids);
        List<Field> list = new ArrayList<>();
        ids = new ArrayList<>();
        for (int i = 0; i < fieldList.size(); i++) {
            if (fieldList.get(i).getIsDerivative() == 1) {
                ids.addAll(StringUtil.toLongList(fieldList.get(i).getOrigFieldId()));
            } else
                list.add(fieldList.get(i));
        }
        if (ids.size() > 0) {
            List<Field> lists = fieldService.findFieldByIdsbyorganId(organId, ids);
            list.addAll(lists);
        }

        List<Field> fields = new ArrayList<>();
        fields.addAll(list);

        this.getEngineField(fields, inputParam);
//        ?????????getEngineField???????????????
//        for (Field field : fieldList) {
//            if (field.getIsDerivative() == 1) {
//                inputParam.put(field.getFieldEn(), "");
//                this.getFieldResult(inputParam);
//            }
//        }
        return false;
    }

    /**
     * ??????http??????????????????????????????????????????
     *
     * @return ??????????????????
     * @see
     */
    @Override
    public boolean getEngineField(List<Field> fields, Map<String, Object> inputParam) {
        logger.info("start getEngineField, fields:{},inputParam:{}", JSONObject.toJSONString(fields), JSONObject.toJSONString(inputParam));

        if (null != fields && fields.size() < 1) {
            return true;
        }
        // ????????????????????????
        List<Field> tempFields = new ArrayList<>(fields);
        for (Field field : fields) {
            if (field.getFieldEn().contains("[") && field.getFieldEn().contains("]")) {
                String fieldEn = field.getFieldEn().substring(0, field.getFieldEn().indexOf("["));
                Field nField = new Field();
                nField.setFieldEn(fieldEn);
                tempFields.add(nField);
                tempFields.remove(field);
            }
        }
        fields = new ArrayList<>(tempFields);

        // ???????????????????????????????????????
        List<Field> remainFields = new ArrayList<>(fields);

        for (Field field : fields) {
            if (inputParam.containsKey(field.getFieldEn())) {
                // ??????????????????????????????
                remainFields.remove(field);
            } else if (field.getType() != null && field.getType() == 5) {
                // ????????????
                // ??????JOSN???????????????????????????????????????value
                String value = field.getJsonValue();
                inputParam.put(field.getFieldEn(), JSONObject.parseObject(value));
                remainFields.remove(field);
            } else if (field.getIsUseSql()) {
                // ??????sql??????????????????
                Object value = getFieldValueBySql(field, inputParam);
                inputParam.put(field.getFieldEn(), value);
                remainFields.remove(field);
            } else if (field.getIsInterface()) {
                // ??????????????????
                String value = getFieldValueByInterface(field, inputParam);
                inputParam.put(field.getFieldEn(), value);
                remainFields.remove(field);
            }
        }

        if (null != remainFields && remainFields.size() < 1) {
            return true;
        }
        for (Field field : remainFields) {
            if (field.getIsDerivative() == 1) {
                //????????????
                inputParam.put(field.getFieldEn(), "");
                this.getFieldResult(inputParam);
                Object value = inputParam.get(field.getFieldEn());
            }
        }
        return false;
    }

    @Override
    public Map<String, Object> getFields(List<Field> fields, Map<String, Object> inputParam) {
        logger.info("start getEngineField, fields:{},inputParam:{}", JSONObject.toJSONString(fields), JSONObject.toJSONString(inputParam));
        Map<String, Object> result = new HashMap<>();
        if (null != fields && fields.size() < 1) {
            return result;
        }
        // ????????????????????????
        List<Field> tempFields = new ArrayList<>(fields);
        for (Field field : fields) {
            if (field.getFieldEn().contains("[") && field.getFieldEn().contains("]")) {
                String fieldEn = field.getFieldEn().substring(0, field.getFieldEn().indexOf("["));
                Field nField = new Field();
                nField.setFieldEn(fieldEn);
                tempFields.add(nField);
                tempFields.remove(field);
            }
        }
        fields = new ArrayList<>(tempFields);

        // ???????????????????????????????????????
        List<Field> remainFields = new ArrayList<>(fields);

        for (Field field : fields) {
            Object value = null;
            if (inputParam.containsKey(field.getFieldEn())) {
                // ??????????????????????????????
                remainFields.remove(field);
                value = inputParam.get(field.getFieldEn());
            } else if (field.getType() != null && field.getType() == 5) {
                // ????????????
                // ??????JOSN???????????????????????????????????????value
                value = field.getJsonValue();
                inputParam.put(field.getFieldEn(), value);
                remainFields.remove(field);
            } else if (field.getIsUseSql()) {
                // ??????sql??????????????????
                value = getFieldValueBySql(field, inputParam);
                inputParam.put(field.getFieldEn(), value);
                remainFields.remove(field);
            } else if (field.getIsInterface()) {
                // ??????????????????
                value = getFieldValueByInterface(field, inputParam);
                inputParam.put(field.getFieldEn(), value);
                remainFields.remove(field);
            }
            if (value != null) {
                result.put(field.getFieldEn(),value);
            }
        }

        if (null != remainFields && remainFields.size() < 1) {
            return result;
        }
        for (Field field : remainFields) {
            if (field.getIsDerivative() == 1) {
                //????????????
                inputParam.put(field.getFieldEn(), "");
                this.getFieldResult(inputParam);
                Object value = inputParam.get(field.getFieldEn());
                if (value != null) {
                    result.put(field.getFieldEn(),value);
                }
            }
        }
//        int type = 1;
//        Properties p = new Properties();
//        try {
//            InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("datacenter.properties");
//            p.load(inputStream);
//        } catch (Exception e1) {
//            e1.printStackTrace();
//            logger.error("remainFields:{},????????????", JSONObject.toJSONString(remainFields), e1);
//        }
//        String act = p.getProperty("act");
//        //?????????
//        String nonce = UUID.randomUUID().toString();
//        String token = p.getProperty("token");
//        Date date = new Date();
//        long ts = date.getTime();
////        String sign = MD5.GetMD5Code(act.trim() + "," + date.getTime() + "," + nonce.trim() + "," + pid.trim() + "," + uid.trim() + "," + token.trim());
//        HttpClient httpClient = new HttpClient();
//        String url = p.getProperty("url") + "?token=" + token.trim() + "&ts=" + ts + "&act=" + act.trim() + "&nonce=" + nonce.trim();
//        Map<String, String> pam = new HashMap<>();
//        pam.put("fields", getListFieldByString(remainFields));
////        pam.put("sign", sign);
//        pam.put("type", String.valueOf(type));
//        try {
//            String result = httpClient.post(url, pam);
//            JSONObject jsonObject = JSONObject.parseObject(result);
//            //??????????????????????????????????????????
//            if (jsonObject.getString("status").equals("0x0000")) {
//                JSONArray array = jsonObject.getJSONArray("data");
//
//                if (type == 1) { //????????????
//                    for (int i = 0; i < array.size(); i++) {
//                        JSONObject object = array.getJSONObject(i);
//                        for (Map.Entry<String, Object> entry : object.entrySet()) {
//                            inputParam.put(entry.getKey(), entry.getValue());
//                        }
//                    }
//                    return true;
//                } else { //????????????
//                    List<ComplexRule> list = new ArrayList<ComplexRule>();
//                    return true;
//                }
//            } else {
//                //?????????????????????,????????????null
//                return false;
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//            logger.error("remainFields:{},????????????", JSONObject.toJSONString(remainFields), e);
//        }
        return result;
    }

//    /**
//     * ???list??????????????????,????????????string?????????
//     *
//     * @inputParam list
//     * @return
//     * @see
//     */
//    private String getListFieldByString(List<Field> list) {
//        String fields = "";
//        for (int i = 0; i < list.size(); i++) {
//            fields = fields + list.get(i).getFieldEn() + ",";
//        }
//        return fields;
//
//    }

    /**
     * ??????sql????????????
     *
     * @param field
     * @return
     */
    private Object getFieldValueBySql(Field field, Map<String, Object> inputParam) {
        logger.info("start?????????sql?????????, fieldEn:{}", field.getFieldEn());
        long start = System.currentTimeMillis();
        DataSource dataSource = dataSourceService.getDataSourceByIdRunner(field.getDataSourceId());
        if (dataSource == null || dataSource.getStatus() == 0) {
            return null;
        }
        DruidDataSource dynamicDataSource = null;
        Jedis jedis = null;
        boolean isDurid = false;
        String keyMd5 = null;
        Object resultValue = null;
        Map<String, Object> parameterMap = new HashMap<>(); // ????????????????????????
        //????????????
        this.getSqlFieldParam(field, inputParam, parameterMap);
        try {
            switch (dataSource.getType()) {
                case DataBaseReSource.MySql.type:
                    dynamicDataSource = DataBaseReSource.MySql.getDataSource(dataSource);
                    isDurid = true;
                    break;
                case DataBaseReSource.Oracle.type:
                    dynamicDataSource = DataBaseReSource.Oracle.getDataSource(dataSource);
                    isDurid = true;
                    break;
                case DataBaseReSource.Redis.type:
                    String userName = dataSource.getUserName();
                    if (StringUtils.isBlank(userName)) {
                        userName = "root";
                    }
                    keyMd5 = CommonConst.JEDIS_KSESSION_KEY_PREFIX + MD5.GetMD5Code(dataSource.getHost() + ":" +
                            dataSource.getPort() + ":" + dataSource.getDbName() + ":" + userName + ":" + dataSource.getPassword());
                    jedis = DataBaseReSource.Redis.getDataSource(dataSource, keyMd5);
                    break;
            }

            if (isDurid) {
                // ??????????????????????????????????????????
                Map<Object, Object> dataSourceMap = DynamicDataSource.getInstance().getDataSourceMap();
                String dataSourceKey = "dynamic-" + dataSource.getId();
                dataSourceMap.put(dataSourceKey, dynamicDataSource);
                DynamicDataSource.getInstance().setTargetDataSources(dataSourceMap);
                DataSourceContextHolder.setDBType(dataSourceKey);
                resultValue = handlerSqlFieldResult(field, parameterMap);
            } else if (jedis != null) {
                resultValue = jedis.eval(parameterMap.get("sqlStr").toString());
            }
            if (resultValue == null) {
                throw new ApiException(ErrorCodeEnum.GET_DATABASE_FIELD_ERROR.getCode(), ErrorCodeEnum.GET_DATABASE_FIELD_ERROR.getMessage());
            }
        } finally {
            // ???????????????????????????????????????
            DataSourceContextHolder.setDBType("default");
            if (StringUtils.isNotBlank(keyMd5) && jedis != null) {
                redisKSessionPool.returnObject(keyMd5, jedis);
            }
        }
        long end = System.currentTimeMillis();
        fieldCallLogService.save(new FieldCallLog(field.getId(), FieldTypeConsts.DATABASE,dataSource.getType(),dataSource.getId().longValue(),JSON.toJSONString(parameterMap),
                JSON.toJSONString(resultValue),(end - start),field.getOrganId()));
        logger.info("???????????????????????????fieldEn:{}, ??????:{}, result:{}, parameterMap:{}", field.getFieldEn(), (end - start), resultValue, parameterMap);
        return resultValue;

    }

    /**
     * ??????????????????
     *
     * @param field
     * @return
     */
    private String getFieldValueByInterface(Field field, Map<String, Object> inputParam) {
        long start = System.currentTimeMillis();
        InterfaceInfo interfaceInfo = interfaceService.getInterfaceById(field.getInterfaceId());
        //http????????????json?????????
        String response = interfaceService.getHttpResponse(interfaceInfo, inputParam, null);
        //??????????????????
        String resultValue ;
        try {
            resultValue = interfaceService.interfaceParseField(field.getInterfaceParseField(), response);
            logger.info("?????????????????????????????????fieldEn:{},fieldCn:{}, fieldValue:{}, response:{}",field.getFieldEn(),field.getFieldCn(),resultValue,response);
        } catch (Exception e) {
            logger.error("?????????????????????????????????fieldEn:{},fieldCn:{},response:{}",field.getFieldEn(),field.getFieldCn(),response);
            e.printStackTrace();
            throw new ApiException(ErrorCodeEnum.GET_INTERFACE_FIELD_ERROR.getCode(),ErrorCodeEnum.GET_INTERFACE_FIELD_ERROR.getMessage());
        }
        long end = System.currentTimeMillis();
        fieldCallLogService.save(new FieldCallLog(field.getId(), FieldTypeConsts.INTERFACE,FieldTypeConsts.INTERFACE,interfaceInfo.getId().longValue(),JSON.toJSONString(inputParam),
                JSON.toJSONString(resultValue),(end - start),field.getOrganId()));
        return resultValue;
    }

    /**
     * ???????????????????????????????????????????????????
     * ???????????????id???????????????(?????????????????????????????????????????????????????????????????????????????????)
     * ????????????????????????????????????????????????
     */
    @Override
    public void getFieldResult(Map<String, Object> paramMap) {
        //????????????????????????Map
        Map<String, Object> paramMap2 = new HashMap<String, Object>();
        for (Map.Entry<String, Object> entry : paramMap.entrySet()) {
            if (null != entry.getValue())
                paramMap2.put(entry.getKey(), entry.getValue());
            else
                paramMap2.put(entry.getKey(), "");
        }

        SessionData sessionData = RunnerSessionManager.getSession();
        Long organId = sessionData.getOrganId();
        paramMap2.put("organId", organId);

        List<FieldCallLog> fieldCallLogs = new ArrayList<>();
        for (Map.Entry<String, Object> entry : paramMap.entrySet()) {
            String fieldEn = entry.getKey();
            String fieldValue = "";
            if (null != entry.getValue())
                fieldValue = String.valueOf(entry.getValue());

            if (null == fieldValue || fieldValue.equals("")) {

                paramMap2.put("fieldEn", fieldEn);
                Field field = fieldService.findByFieldEnbyorganId(organId, fieldEn);

                if (field != null) {
                    if (field.getIsDerivative() == 1) {
                        long start = System.currentTimeMillis();
                        String result = "";
                        paramMap2.put("fieldCn", field.getFieldCn());
                        result = getExpAll(field.getFieldCn(), "", paramMap);
                        //?????????????????????????????????()??????????????????
                        result = result.replace("(", "");
                        result = result.replace(")", "");
                        long end = System.currentTimeMillis();
                        fieldCallLogs.add(new FieldCallLog(field.getId(),FieldTypeConsts.DERIVE,FieldTypeConsts.DERIVE,
                                FieldTypeConsts.DERIVE_SOURCE_ID,JSON.toJSONString(paramMap),result,(end-start),field.getOrganId()));
                        paramMap.put(fieldEn, result);
                    }
                }
            }
        }
        if (!fieldCallLogs.isEmpty()){
            fieldCallLogService.saveBatch(fieldCallLogs);
        }
    }

    /**
     * ????????????????????????????????????????????????????????????????????????
     */
    private String getExpAll(String fieldCn, String exp, Map<String, Object> param) {

        String result = "";
        Map<String, Object> param2 = new HashMap<String, Object>();
        for (Map.Entry<String, Object> entry : param.entrySet()) {
            if (null != entry.getValue())
                param2.put(entry.getKey(), entry.getValue());
            else
                param2.put(entry.getKey(), "");
        }

        SessionData sessionData = RunnerSessionManager.getSession();
        Long organId = sessionData.getOrganId();

        Map<String, Object> paramMap = new HashMap<String, Object>();

        paramMap.put("organId", organId);
        paramMap.put("engineId", param.get("engineId"));
        paramMap.put("fieldCn", fieldCn);

        //????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
        String arrFormula = "";
        Field engField = fieldService.findByFieldCnbyorganId(organId, fieldCn);
        String engFormula = engField.getFormula();
        if (!engFormula.equals("") && engFormula != null) {
            arrFormula = engFormula;
        }

        if (arrFormula.equals("") || arrFormula == null) { //??????????????????????????????

            List<FieldCond> fieldCondList = new ArrayList<FieldCond>();
            List<FieldCond> engfieldCondList = fieldService.findByFieldCnbyorganId(organId, fieldCn).getFieldCondList();
            if (engfieldCondList.size() > 0) {
                fieldCondList = engfieldCondList;
            }

            if (fieldCondList.size() > 0) {//????????????????????????????????????
                for (FieldCond fieldCond : fieldCondList) {//?????????fieldCond?????????????????????????????????
                    String condValue = fieldCond.getConditionValue();
                    List<Object> condList = new ArrayList<>();
                    condList = JSONObject.parseArray(fieldCond.getContent());
                    exp = "";
                    for (int j = 0; j < condList.size(); j++) {
                        JSONObject cond = ((JSONArray) condList).getJSONObject(j);
                        //[{\"fieldId\":\"31\",\"operator\":\">\",\"fieldValue\":\"1000\",\"logical\":\"&&\"},{\"fieldId\":\"31\",\"operator\":\">=\",\"fieldValue\":\"7000\"}]
                        paramMap.put("id", cond.getString("fieldId"));

                        Field condfield = fieldService.queryById(Long.valueOf(cond.getString("fieldId")));
                        if (condfield == null) {
                            condfield = fieldService.findByFieldCnbyorganId(organId, fieldCn);
                        }

                        String condFieldEn = condfield.getFieldEn();//yqshouru ?????????
                        String condFieldCn = condfield.getFieldCn();
                        Integer condValueType = condfield.getValueType(); //1?????????
                        String condFieldValue = cond.getString("fieldValue"); //1000
                        String operator = cond.getString("operator"); //>?????????
                        String fieldValue = param2.get(condFieldEn).toString(); //?????????????????????

                        String logical = "";

                        if (condfield.getIsDerivative() == 0) {
                            if (cond.containsKey("logical"))
                                logical = " " + cond.getString("logical") + " ";
                            if (operator.equals("in")) {
                                //exp += "(indexOf(#{"+fieldValue+"},'"+condFieldValue+"')>0"+logical;
                                exp += "(indexOf('" + condFieldValue + "','" + fieldValue + "',0) >= 0)" + logical;
                            } else if (operator.equals("not in")) {
                                //exp += "(indexOf(#{"+fieldValue+"},'"+condFieldValue+"')=0"+logical;
                                exp += "(indexOf('" + condFieldValue + "','" + fieldValue + "',0) = -1)" + logical;
                            } else if (operator.equals("like")) { //???????????? (indexOf('abc','c',0) >= 0)
                                exp += "(indexOf('" + fieldValue + "','" + condFieldValue + "',0) >= 0)" + logical;
                            } else if (operator.equals("not like")) { //(indexOf('abc','x',0) = -1)
                                exp += "(indexOf('" + fieldValue + "','" + condFieldValue + "',0) = -1)" + logical;
                            } else {
                                if (condValueType == 1 || condValueType == 4) {
                                    exp += " (" + fieldValue + "" + operator + "" + condFieldValue + ") " + logical;
                                } else
                                    exp += " ('" + fieldValue + "'" + operator + "'" + condFieldValue + "') " + logical;
                            }
                        } else {//????????????
                            if (cond.containsKey("logical"))
                                logical = " " + cond.getString("logical") + " ";
                            if (operator.equals("in")) {
                                //exp += "(indexOf(#{"+getExpAll(condFieldCn,"",param2)+"},'"+condFieldValue+"')>0"+logical;
                                //(indexOf('abc','c',0) >= 0) && (indexOf('abc','x',0) = -1)
                                exp += "(indexOf('" + condFieldValue + "','" + getExpAll(condFieldCn, "", param2) + "',0) >= 0)" + logical;
                            } else if (operator.equals("not in")) {
                                exp += "(indexOf('" + condFieldValue + "','" + getExpAll(condFieldCn, "", param2) + "',0) = -1)" + logical;
                            } else if (operator.equals("like")) { //???????????? (indexOf('abc','c',0) >= 0)
                                exp += "(indexOf('" + getExpAll(condFieldCn, "", param2) + "','" + condFieldValue + "',0) >= 0)" + logical;
                            } else if (operator.equals("not like")) { //(indexOf('abc','x',0) = -1)
                                exp += "(indexOf('" + getExpAll(condFieldCn, "", param2) + "','" + condFieldValue + "',0) = -1)" + logical;
                            } else {
                                if (condValueType == 1 || condValueType == 4) {
                                    exp += " (" + getExpAll(condFieldCn, "", param2) + "" + operator + "" + condFieldValue + ") " + logical;
                                } else
                                    exp += " ('" + getExpAll(condFieldCn, "", param2) + "'" + operator + "'" + condFieldValue + "') " + logical;
                            }
                        }
                    }
                    Evaluator evaluator = new Evaluator();
                    String b = "";
                    try {
                        System.out.println("========??????????????????????????????????????????" + exp);
                        b = evaluator.evaluate(exp);
                    } catch (EvaluationException e) {
                        e.printStackTrace();
                        logger.error("????????????", e);
                    }
                    if (b.equals("1.0")) {
                        result = condValue;
                        break; //??????????????????????????????????????????
                    }
                }
            }
        } else { //????????????????????????
            List<Object> formulaList = new ArrayList<>();
            formulaList = JSONObject.parseArray(arrFormula);
            for (int i = 0; i < formulaList.size(); i++) {
                JSONObject formulaJson = ((JSONArray) formulaList).getJSONObject(i);

                String formula = (String) formulaJson.get("formula");
                formula = formula.replace("&gt;", ">"); //3&gt;=6 && 3&lt; 12
                formula = formula.replace("&lt;", "<");
                Pattern pattern = Pattern.compile("@[a-zA-Z0-9_\u4e00-\u9fa5()??????-]+@");
                Matcher matcher = pattern.matcher(formula);
                String subexp = formula;
                int j = 0;
                exp = "";
                // ??????groovy????????????
                Map<String, Object> data = new HashMap<>();
                //System.out.println("????????????????????????"+formula);
                while (matcher.find()) {
                    String fieldCN = matcher.group(0).replace("@", "");
                    Map<String, Object> fieldMap = new HashMap<String, Object>();
                    paramMap.put("organId", organId);
                    fieldMap.put("engineId", paramMap.get("engineId"));
                    fieldMap.put("fieldCn", fieldCN);
                    fieldMap.put("organId", organId);
                    Field subField = fieldService.findByFieldCnbyorganId(organId, fieldCN);

                    //??????????????????????????????????????????
                    Map<String, Object> paramCond = new HashMap<String, Object>();
                    paramCond.put("fieldValue", param2.get(subField.getFieldEn()));
                    paramCond.put("fieldEn", subField.getFieldEn());
                    paramCond.put("fieldValueType", subField.getValueType());

                    //??????????????????
                    JSONArray fieldCond = new JSONArray();
                    if (formulaJson.get("farr") != null && !"".equals(formulaJson.get("farr"))) {
                        JSONArray jsonArr = (JSONArray) formulaJson.get("farr");
                        for (Iterator iterator = jsonArr.iterator(); iterator.hasNext(); ) {
                            JSONObject job = (JSONObject) iterator.next();
                            if (job.get("fieldCN").equals(fieldCN) && !job.get("fieldCond").equals("")) {
                                fieldCond = (JSONArray) job.get("fieldCond");
                                break;
                            }
                        }
                    }

                    paramCond.put("fieldCond", fieldCond);
                    String v = "";
                    if (fieldCond.size() > 0) {
                        v = calcFieldCond(paramCond);
                    } else {
                        v = "" + param2.get(subField.getFieldEn());
                    }
                    data.put(subField.getFieldEn(), param2.get(subField.getFieldEn()));

                    if (subField.getIsDerivative() == 0) {
//						if(subexp.indexOf("substring")>=0||subexp.indexOf("equals")>=0){ //substring(@??????A@,3,6)
//							exp += subexp.substring(j, matcher.end()).replace("@"+fieldCN+"@", "'"+v+"'");
//						}else{
//							exp += subexp.substring(j, matcher.end()).replace("@"+fieldCN+"@", v);
//						}

                        if (subexp.contains("def main")) {
                            // groovy???????????????????????????
                            v = "_['" + subField.getFieldEn() + "']";
                            exp += subexp.substring(j, matcher.end()).replace("@" + fieldCN + "@", v);
                        } else {
                            if (subField.getValueType() == 1 || subField.getValueType() == 4) {
                                exp += subexp.substring(j, matcher.end()).replace("@" + fieldCN + "@", v);
                            } else {
                                exp += subexp.substring(j, matcher.end()).replace("@" + fieldCN + "@", "'" + v + "'");
                            }
                        }

                    } else {

                        v = getExpAll(fieldCN, exp, param2);
                        // ???????????????
                        if (subField.getValueType() == 1 || subField.getValueType() == 4) {
                            data.put(subField.getFieldEn(), Integer.valueOf(v));
                        } else {
                            data.put(subField.getFieldEn(), v);
                        }

                        if (subexp.contains("def main")) {
                            // groovy???????????????????????????
                            v = "_['" + subField.getFieldEn() + "']";
                        }
                        exp += subexp.substring(j, matcher.end()).replace("@" + fieldCN + "@", v);
                    }
                    j = matcher.end();
                }
                exp += formula.substring(j, formula.length());
                Evaluator evaluator = new Evaluator();
                String b = "";
                try {
//                    System.out.println("========?????????????????????????????????????????????" + exp);

                    if (exp.contains("def main")) {
                        // ??????groovy??????
                        b = groovy.execute(exp, data);
//                        b = String.valueOf(groovy.execute(exp, data));
                    } else {
                        b = evaluator.evaluate(exp);
                    }

                } catch (EvaluationException e) {
                    e.printStackTrace();
                    logger.error("????????????", e);
                }

                //
                if (engField.getValueType().intValue() == 1 || engField.getValueType().intValue() == 2) { //????????????????????????????????????????????????b??????????????????0.0??????????????????
                    if (!b.equals("")) {
                        result = b;
                        if (StringUtil.isValidStr(result) && result.startsWith("'") && result.endsWith("'")) {
                            result = result.substring(1, result.length() - 1);
                        }
                    }
                } else if (engField.getValueType().intValue() == 3) { //?????????????????????????????????b??????????????????0.0??????false????????????????????????
                    if (!b.equals("1.0") && !b.equals("0.0") && !b.equals("")) {
                        result = b;
                        if (StringUtil.isValidStr(result) && result.startsWith("'") && result.endsWith("'")) {
                            result = result.substring(1, result.length() - 1);
                        }
                    }
                    if (b.equals("1.0")) {
                        result = (String) formulaJson.get("fvalue");
                        //result = result.substring(result.indexOf(":")+1,result.length());// a:2 ???2??????
                        if (isNumeric(result)) {
                            result = "(" + result + ")";
                        } else {
                            result = "'" + result + "'";
                        }
                        break; //??????????????????????????????????????????
                    }
                }

            }
        }

        return result;

    }

    /**
     * ????????????????????????????????????????????????????????????
     * ???????????????????????????????????????????????????
     * ???????????????????????????????????????????????????
     */
    private String calcFieldCond(Map<String, Object> paramMap) {

        String fieldValue = (String) paramMap.get("fieldValue");
        Integer fieldValueType = (Integer) paramMap.get("fieldValueType");

        String result = "";
        //[{"fieldCN":"????????????1-1","fieldCond":[{"inputOne":"a","inputThree":"33"},{"inputOne":"b","inputThree":"490"},{"inputOne":"c","inputThree":"50"}]}]
        //[{"fieldCN":"????????????1-1","fieldCond":[{"inputOne":"(3,19]","inputThree":"1"},{"inputOne":"(19,200]","inputThree":"2"}]},{"fieldCN":"????????????2??????","fieldCond":""}]
        JSONArray jsonArr = (JSONArray) paramMap.get("fieldCond");
        for (Iterator iterator = jsonArr.iterator(); iterator.hasNext(); ) {
            JSONObject job = (JSONObject) iterator.next();
            String inputOne = (String) job.get("inputOne");
            String inputThree = (String) job.get("inputThree");

            if (fieldValueType == 3) {
                if (fieldValue.equals(inputOne)) {
                    result = inputThree;
                    break;
                }
            } else if (fieldValueType == 1 || fieldValueType == 4) {
                //(40,50]
                Double lv = Double.parseDouble(inputOne.substring(1, inputOne.indexOf(",")));
                Double rv = Double.parseDouble(inputOne.substring(inputOne.indexOf(",") + 1, inputOne.length() - 1));

                String exp = "";
                if (inputOne.startsWith("(") && !lv.equals("")) {
                    exp = fieldValue + ">" + lv;
                }
                if (inputOne.startsWith("[") && !lv.equals("")) {
                    exp = fieldValue + ">=" + lv;
                }
                if (inputOne.endsWith(")") && !rv.equals("")) {
                    if (exp.equals(""))
                        exp += fieldValue + "<" + rv;
                    else
                        exp += "&&" + fieldValue + "<" + rv;
                }
                if (inputOne.endsWith("]") && !rv.equals("")) {
                    if (exp.equals(""))
                        exp += fieldValue + "<=" + rv;
                    else
                        exp += "&&" + fieldValue + "<=" + rv;
                }

                Evaluator evaluator = new Evaluator();
                String b = "";
                try {
                    b = evaluator.evaluate(exp);
                } catch (EvaluationException e) {
                    e.printStackTrace();
                    logger.error("????????????", e);
                }
                if (b.equals("1.0")) {
                    result = inputThree;
                    break; //??????????????????????????????????????????
                }
            }
        }
        return result;
    }

    /**
     * ????????????????????????????????????????????????????????????
     */
    private boolean isNumeric(String str) {
        Pattern pattern = Pattern.compile("^(-|\\+)?\\d+(\\.\\d+)?$");
        Matcher isNum = pattern.matcher(str);
        if (!isNum.matches()) {
            return false;
        }
        return true;
    }

    /**
     * ????????????????????????????????????
     */
    private Map getSqlFieldParam(Field field, Map<String, Object> inputParam, Map<String, Object> parameterMap) {
        String sqlStr = field.getSqlStatement();
        // ??????????????????
        //??????in??????????????????
        Pattern sqlInPattern = Pattern.compile("[\\s]*in[\\s]*\\([\\s]*#\\{([a-zA-Z0-9_\u4e00-\u9fa5()??????-]+)\\}[\\s]*\\)");
        Matcher sqlInMatcher = sqlInPattern.matcher(sqlStr);
        while (sqlInMatcher.find()) {
            String replaceOld = sqlInMatcher.group(0);
            String sqlField = sqlInMatcher.group(1);
            String sqlVariable = field.getSqlVariable();
            String fieldEn = sqlField.split("\\.")[0];
            String convertStr = "";
            Object value = null;
            if (StringUtils.isNotBlank(sqlVariable)) {
                JSONArray sqlVariableArr = JSONArray.parseArray(sqlVariable);
                for (int i = 0; i < sqlVariableArr.size(); i++) {
                    JSONObject sqlVariableObj = sqlVariableArr.getJSONObject(i);
                    if (sqlField.equals(sqlVariableObj.getString("key"))) {
                        value = sqlVariableObj.getJSONArray("value");
                    }
                }
            }
            if (value == null) {
                if (!inputParam.containsKey(fieldEn)) {
                    //???????????????????????????
                    List<String> fieldEns = new ArrayList<>();
                    fieldEns.add(fieldEn);
                    //??????????????????en?????????????????????????????????????????????u??????????????????
                    List<Field> fieldList = fieldService.selectFieldListByEns(fieldEns);
                    if (fieldList != null && !fieldList.isEmpty()) {
                        //????????????????????????
                        getEngineField(fieldList, inputParam);
                    }

                }
//                    value = inputParam.get(sqlField);
                value = ExecuteUtils.getObjFromMap(inputParam, sqlField);
            }
            if (StringUtils.isBlank(convertStr) && value != null) {
                if (value instanceof String) {
                    convertStr = value.toString();
                } else if (value instanceof List) {
                    List collection = (List) value;
                    int size = collection.size();

                    for (int i = 0; i < size; i++) {
                        convertStr += ("'" + String.valueOf(collection.get(i)) + "'");
                        if (i < size - 1) {
                            convertStr += ",";
                        }
                    }
                }
            }
            sqlStr = sqlStr.replace(replaceOld, " in (" + convertStr + ") ");
        }
        Pattern pattern = Pattern.compile("#\\{[a-zA-Z0-9_\u4e00-\u9fa5()??????-]+\\}");
        Matcher matcher = pattern.matcher(sqlStr);
        while (matcher.find()) {
            String sqlField = matcher.group(0).replace("#{", "").replace("}", "");
            String fieldEn = sqlField.split("\\.")[0];
            // sql?????????????????????????????????
            String sqlVariable = field.getSqlVariable();
            if (StringUtils.isNotBlank(sqlVariable)) {
                JSONArray sqlVariableArr = JSONArray.parseArray(sqlVariable);
                for (int i = 0; i < sqlVariableArr.size(); i++) {
                    JSONObject sqlVariableObj = sqlVariableArr.getJSONObject(i);
                    if (sqlField.equals(sqlVariableObj.getString("key"))) {
                        parameterMap.put(sqlField, sqlVariableObj.get("value"));
                    }
                }
            }

            // sql??????????????????????????????
            if (!parameterMap.containsKey(fieldEn)) {
                if (!inputParam.containsKey(fieldEn)) {
                    //???????????????????????????
                    List<String> fieldEns = new ArrayList<>();
                    fieldEns.add(fieldEn);
                    //??????????????????en?????????????????????????????????????????????u??????????????????
                    List<Field> fieldList = fieldService.selectFieldListByEns(fieldEns);
                    if (fieldList != null && !fieldList.isEmpty()) {
                        //????????????????????????
                        getEngineField(fieldList, inputParam);
                    }

                }
                parameterMap.put(sqlField, ExecuteUtils.getObjFromMap(inputParam, sqlField));
            }
        }
        Pattern pattern$ = Pattern.compile("\\$\\{[a-zA-Z0-9_\u4e00-\u9fa5()??????-]+\\}");
        Matcher matcher$ = pattern$.matcher(sqlStr);
        while (matcher$.find()) {
            String sqlField = matcher$.group(0).replace("${", "").replace("}", "");
            String fieldEn = sqlField.split("\\.")[0];
            // sql?????????????????????????????????
            String sqlVariable = field.getSqlVariable();
            String dictVariable = field.getDictVariable();
            String replaceStr = "";
            if (StringUtils.isNotBlank(sqlVariable)) {
                JSONArray sqlVariableArr = JSONArray.parseArray(sqlVariable);
                for (int i = 0; i < sqlVariableArr.size(); i++) {
                    JSONObject sqlVariableObj = sqlVariableArr.getJSONObject(i);

                    if (!sqlField.equals(sqlVariableObj.getString("key"))) {
                        continue;
                    }
                    if (inputParam.containsKey(fieldEn)) {
                        replaceStr = ExecuteUtils.getObjFromMap(inputParam, sqlField).toString();
                    } else if (sqlVariableObj.get("value") != null) {
                        replaceStr = String.valueOf(sqlVariableObj.get("value"));
                    }
                    if (StringUtils.isNotBlank(replaceStr)) {
                        break;
                    }

                }
            }
            if (StringUtils.isNotBlank(dictVariable)) {
                JSONArray jsonArray = JSONArray.parseArray(dictVariable);
                for (int i = 0; i < jsonArray.size(); i++) {
                    JSONObject jsonObject = jsonArray.getJSONObject(i);
                    if (!sqlField.equals(jsonObject.getString("key"))) {
                        continue;
                    }
//                    if (inputParam.containsKey(fieldEn)) {
//                        replaceStr = ExecuteUtils.getObjFromMap(inputParam, sqlField).toString();
//                    } else
//                    if (jsonObject.get("value") != null) {
//                        switch (jsonObject.getString("type")){
//                            case "date":
//                                try {
//                                    replaceStr = DateUtil.format(new Date(),jsonObject.getString("value"));
//                                }catch (Exception e){
//                                    e.printStackTrace();
//                                    replaceStr = DateUtil.format(new Date(),"yyyyMMdd");
//                                }
//                                break;
//                            default:
//                                replaceStr = String.valueOf(jsonObject.get("value"));
//                        }
//                    }
                    replaceStr = DictVariableUtils.getValueFromJsonObject(jsonObject).toString();
                    if (StringUtils.isNotBlank(replaceStr)) {
                        break;
                    }
                }
            }
            if (StringUtils.isNotBlank(replaceStr)) {
                //???????????????????????????
                sqlStr = sqlStr.replace("${" + sqlField + "}", replaceStr);
                continue;
            }
            // sql??????????????????????????????
            if (!parameterMap.containsKey(fieldEn)) {
                if (!inputParam.containsKey(fieldEn)) {
                    //???????????????????????????
                    //??????????????????en?????????????????????????????????????????????u??????????????????
                    List<Field> fieldList = fieldService.selectFieldListByEns(Arrays.asList(fieldEn));
                    if (fieldList != null && !fieldList.isEmpty()) {
                        //????????????????????????
                        getEngineField(fieldList, inputParam);
                    }
                }
                replaceStr = ExecuteUtils.getObjFromMap(inputParam, sqlField).toString();
            }
            if (StringUtils.isNotBlank(replaceStr)) {
                sqlStr = sqlStr.replace("${" + sqlField + "}", replaceStr);
                break;
            }
        }
        parameterMap.put("sqlStr", sqlStr);
        return parameterMap;
    }

    /**
     * ??????????????????????????????sql????????????
     */
    private Object handlerSqlFieldResult(Field field, Map<String, Object> parameterMap) {
        List<LinkedHashMap<String, Object>> result = simpleMapper.customSelect(parameterMap);
        Object resultValue = null;
        if (result == null || result.size() == 0) {
            resultValue = null;
        } else {
            //json???????????????
            if (field.getValueType() == 6) {
                String json = field.getJsonValue();
                //?????????????????????
                if (StringUtils.isNotBlank(json) && json.startsWith("[") && json.endsWith("]")) {
                    resultValue = result;
                } else {
                    resultValue = result.get(0);
                }
            } else if (result.size() == 1) {// ????????????????????????????????????????????????sql?????????????????????????????????????????????
                LinkedHashMap<String, Object> resultMap = result.get(0);
                if (resultMap.size() == 1) {
                    for (Map.Entry<String, Object> entry : resultMap.entrySet()) {
                        Object value = entry.getValue();

                        // ??????double????????????????????????
                        if (value instanceof Double) {
                            value = BigDecimal.valueOf((Double) value);
                        }
                        resultValue = value.toString();
                    }
                } else {
                    throw new RuntimeException("sql????????????????????????sql???????????????????????????????????????resultMap:" + resultMap.toString());
                }
            } else {
                throw new RuntimeException("sql????????????????????????sql???????????????????????????????????????result:" + result.toString());
            }
        }
        return resultValue;
    }
}
