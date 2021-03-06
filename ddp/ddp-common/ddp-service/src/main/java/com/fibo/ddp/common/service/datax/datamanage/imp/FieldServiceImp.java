package com.fibo.ddp.common.service.datax.datamanage.imp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fibo.ddp.common.dao.canal.TableEnum;
import com.fibo.ddp.common.dao.datax.datamanage.*;
import com.fibo.ddp.common.dao.strategyx.knowledge.RuleMapper;
import com.fibo.ddp.common.dao.strategyx.listlibrary.ListDbMapper;
import com.fibo.ddp.common.dao.strategyx.scorecard.ScorecardMapper;
import com.fibo.ddp.common.model.common.enums.ErrorCodeEnum;
import com.fibo.ddp.common.model.common.requestParam.UpdateFolderParam;
import com.fibo.ddp.common.model.datax.common.ExcelUtil;
import com.fibo.ddp.common.model.datax.common.Status;
import com.fibo.ddp.common.model.datax.datamanage.Field;
import com.fibo.ddp.common.model.datax.datamanage.FieldCond;
import com.fibo.ddp.common.model.datax.datamanage.FieldUser;
import com.fibo.ddp.common.model.strategyx.knowledge.Rule;
import com.fibo.ddp.common.model.strategyx.listlibrary.ListDb;
import com.fibo.ddp.common.model.strategyx.scorecard.Scorecard;
import com.fibo.ddp.common.service.common.runner.RunnerSessionManager;
import com.fibo.ddp.common.service.common.SessionManager;
import com.fibo.ddp.common.service.datax.datamanage.FieldService;
import com.fibo.ddp.common.service.redis.RedisManager;
import com.fibo.ddp.common.service.redis.RedisUtils;
import com.fibo.ddp.common.utils.constant.Constants;
import com.fibo.ddp.common.utils.exception.ApiException;
import com.fibo.ddp.common.utils.util.StringUtil;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class FieldServiceImp extends ServiceImpl<FieldMapper,Field> implements FieldService {

    @Autowired
    private FieldMapper fieldMapper;
    @Autowired
    private FieldUserMapper fieldUserMapper;
    @Autowired
    private FieldCondMapper fieldCondMapper;
    @Autowired
    private ListDbMapper listDbMapper;
    @Autowired
    private RuleMapper ruleMapper;
    @Autowired
    private ScorecardMapper scorecardMapper;
    @Autowired
    private FieldTypeUserMapper fieldTypeUserMapper;
    @Autowired
    private FieldTypeMapper fieldTypeMapper;
    @Autowired
    private RedisManager redisManager;

    // ??????????????????????????????
    @Value("${switch.use.cache}")
    private String cacheSwitch;

    protected static final Set<String> KEY_WORDS = new HashSet<String>() {{
        add("DELETE ");
        add("DROP ");
        add("TRUNCATE ");
        add("UPDATE ");
        add("ALTER ");
        add("INSERT ");
        add("CREATE ");
        add("RENAME ");
    }};

    /*
     * ?????????????????????id????????????id
     */
    public StringBuffer getUniqueStr(String usedFieldStr) {

        String arrUsedFieldStr[] = usedFieldStr.split(",");
        Set<String> usedFieldSet = new HashSet<>();
        for (int k = 0; k < arrUsedFieldStr.length; k++) {
            usedFieldSet.add(arrUsedFieldStr[k]);
        }
        String[] arrUsedField = (String[]) usedFieldSet.toArray(new String[usedFieldSet.size()]);
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < arrUsedField.length; i++) {
            if (i != arrUsedField.length - 1)
                sb.append(arrUsedField[i]).append(",");
            else
                sb.append(arrUsedField[i]);
        }
        return sb;
    }

    @Override
    public boolean createField(Field fieldVo, Map<String, Object> paramMap) {

        String formulaHidden = "";

        //???????????????????????????????????????????????????????????????
        if (paramMap.containsKey("formulaHidden") && !paramMap.get("formulaHidden").equals("")) {

            formulaHidden = (String) paramMap.get("formulaHidden");
            fieldVo.setFormula(formulaHidden);

            List<Object> formulaList = new ArrayList<>();
            formulaList = JSONObject.parseArray(formulaHidden);

            JSONArray jsonArrayFormula = new JSONArray();

            String origFieldStr = "";
            String usedFieldStr = "";

            for (int i = 0; i < formulaList.size(); i++) {

                JSONObject f = ((JSONArray) formulaList).getJSONObject(i);
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("fvalue", f.getString("fvalue"));
                jsonObject.put("formula", f.getString("formula"));
                jsonObject.put("idx", f.getString("idx"));
                jsonArrayFormula.add(jsonObject);

                List<Object> farrList = new ArrayList<>();
                String formula = f.getString("formula");
                Pattern pattern = Pattern.compile("@[a-zA-Z0-9_\u4e00-\u9fa5()??????-]+@");
                Matcher matcher = pattern.matcher(formula);
                while (matcher.find()) {
                    String fieldCN = matcher.group(0).replace("@", "");
                    Map<String, Object> fieldMap = new HashMap<String, Object>();
                    fieldMap.put("userId", paramMap.get("userId"));
                    fieldMap.put("engineId", paramMap.get("engineId"));
                    fieldMap.put("fieldCn", fieldCN);

                    Field field = fieldMapper.findByFieldCn(fieldMap);

                    if (field.getOrigFieldId() == null) {
                        if (origFieldStr.equals("")) {
                            origFieldStr = Long.toString(field.getId());
                        } else {
                            origFieldStr = origFieldStr + "," + field.getId();
                        }
                    } else {
                        if (origFieldStr.equals("")) {
                            origFieldStr = field.getOrigFieldId();
                        } else {
                            origFieldStr = origFieldStr + "," + field.getOrigFieldId();
                        }
                    }
                    usedFieldStr = usedFieldStr + field.getId() + ","; //??????????????????????????????id
                }
//				}
            }

            fieldVo.setFormulaShow(JSON.toJSONString(jsonArrayFormula));

            //??????????????????id
            if (!origFieldStr.equals("")) {
                fieldVo.setOrigFieldId(getUniqueStr(origFieldStr).toString());
            }

            //??????????????????id
            if (!usedFieldStr.equals(",") && !usedFieldStr.equals("")) {
                usedFieldStr = usedFieldStr.substring(0, usedFieldStr.length() - 1);
                fieldVo.setUsedFieldId(getUniqueStr(usedFieldStr).toString());
            }

        } else if (paramMap.containsKey("fieldCondList") && !paramMap.get("fieldCondList").equals("")) {
            //????????????????????????????????????????????????
			
			/*
			  fieldContent=[{"fieldContent2":"[{\"fieldId\":\"3\",\"operator\":\">\",\"fieldValue\":\"200\",\"logical\":\"&&\"}
				  							  ,{\"fieldId\":\"11\",\"operator\":\"<\",\"fieldValue\":\"50\"}]","conditionValue":"5","fieldValue":"50"}
						   ,{"fieldContent2":"[{\"fieldId\":\"12\",\"operator\":\"in\",\"fieldValue\":\"z\",\"logical\":\"&&\"}
				  							  ,{\"fieldId\":\"11\",\"operator\":\">\",\"fieldValue\":\"200\",\"logical\":\"&&\"}
				  							  ,{\"fieldId\":\"31\",\"operator\":\">\",\"fieldValue\":\"1000\"}]","conditionValue":"8","fieldValue":"1000"}
						   ,{"fieldContent2":"[{\"fieldId\":\"31\",\"operator\":\">\",\"fieldValue\":\"4000\"}]","conditionValue":"9","fieldValue":"4000"}]
			*/
            String fieldContent = (String) paramMap.get("fieldCondList");
            List<Object> fieldContentList = new ArrayList<>();
            fieldContentList = JSONObject.parseArray(fieldContent);

            String origFieldStr = "";
            String usedFieldStr = "";

            for (int i = 0; i < fieldContentList.size(); i++) {
                JSONObject fc = ((JSONArray) fieldContentList).getJSONObject(i);
                List<Object> farrList = new ArrayList<>();
                if (!fc.getString("fieldSubCond").equals("") && fc.getString("fieldSubCond") != null) {
                    farrList = JSONObject.parseArray(fc.getString("fieldSubCond"));
                    for (int j = 0; j < farrList.size(); j++) {
                        JSONObject ObjField = ((JSONArray) farrList).getJSONObject(j);
                        usedFieldStr = usedFieldStr + ObjField.get("fieldId") + ",";

                        Map<String, Object> fieldMap = new HashMap<String, Object>();
                        fieldMap.put("userId", paramMap.get("userId"));
                        fieldMap.put("engineId", paramMap.get("engineId"));
                        fieldMap.put("id", ObjField.get("fieldId"));
                        Field field = fieldMapper.findByFieldId(fieldMap);

                        if (field.getOrigFieldId() == null) {
                            if (origFieldStr.equals("")) {
                                origFieldStr = Long.toString(field.getId());
                            } else {
                                origFieldStr = origFieldStr + "," + field.getId();
                            }
                        } else {
                            if (origFieldStr.equals("")) {
                                origFieldStr = field.getOrigFieldId();
                            } else {
                                origFieldStr = origFieldStr + "," + field.getOrigFieldId();
                            }
                        }


                    }
                }
            }

            //??????????????????id
            if (!usedFieldStr.equals(",") && !usedFieldStr.equals("")) {
                usedFieldStr = usedFieldStr.substring(0, usedFieldStr.length() - 1);
                fieldVo.setUsedFieldId(getUniqueStr(usedFieldStr).toString());
            }

            //??????????????????id
            if (!origFieldStr.equals("")) {
                fieldVo.setOrigFieldId(getUniqueStr(origFieldStr).toString());
            }
        }

        if (fieldMapper.isExists(paramMap) == 0) {
            fieldVo.setSourceType((Integer) paramMap.getOrDefault("sourceType", 1));
            fieldMapper.createField(fieldVo);
            FieldUser fieldUserVo = new FieldUser();
            fieldUserVo.setFieldId(fieldVo.getId());
            fieldUserVo.setOrganId((Long) paramMap.get("organId"));
            if (paramMap.get("engineId") != null) {
                fieldUserVo.setEngineId(Long.valueOf((String) paramMap.get("engineId")));
            }
            fieldUserVo.setUserId((Long) paramMap.get("userId"));
            fieldUserVo.setStatus(Status.enable.value);
            fieldUserMapper.createFieldUserRel(fieldUserVo);

            //??????????????????????????????,??????????????????
            if (paramMap.containsKey("fieldCondList")) {

                String fieldContent = (String) paramMap.get("fieldCondList");
                if (!fieldContent.equals("")) {
                    List<FieldCond> fieldCondVoList = new ArrayList<FieldCond>();
                    List<Object> condList = new ArrayList<>();
                    condList = JSONObject.parseArray(fieldContent);
                    for (int i = 0; i < condList.size(); i++) {
                        JSONObject cond = ((JSONArray) condList).getJSONObject(i);
                        List<Object> subCondList = new ArrayList<>();
                        if (!cond.getString("fieldSubCond").equals("")) {
                            subCondList = JSONObject.parseArray(cond.getString("fieldSubCond"));
                            for (int j = 0; j < subCondList.size(); j++) {
                                JSONObject subCond = ((JSONArray) subCondList).getJSONObject(j);
                                FieldCond fieldCondVo = new FieldCond();
                                fieldCondVo.setFieldId(fieldVo.getId());
                                fieldCondVo.setConditionValue(cond.getString("conditionValue"));
                                fieldCondVo.setContent(cond.getString("fieldSubCond"));
                                fieldCondVo.setCondFieldId(Long.valueOf(subCond.getString("fieldId")));
                                fieldCondVo.setCondFieldOperator(subCond.getString("operator"));
                                fieldCondVo.setCondFieldValue(subCond.getString("fieldValue"));
                                fieldCondVo.setCondFieldLogical(subCond.getString("logical"));
                                fieldCondVoList.add(fieldCondVo);
                            }
                        }
                        fieldCondMapper.createFieldCond(fieldCondVoList);
                    }

                }
            }

            return true;

        } else
            return false;
    }

    @Override
    public boolean updateField(Map<String, Object> paramMap) {

        String formulaHidden = "";

        if (paramMap.containsKey("formulaHidden") && !paramMap.get("formulaHidden").equals("")) {

            formulaHidden = (String) paramMap.get("formulaHidden");
            paramMap.put("formula", formulaHidden);

            List<Object> formulaList = new ArrayList<>();
            formulaList = JSONObject.parseArray(formulaHidden);

            JSONArray jsonArrayFormula = new JSONArray();

            String origFieldStr = "";
            String usedFieldStr = "";

            for (int i = 0; i < formulaList.size(); i++) {

                JSONObject f = ((JSONArray) formulaList).getJSONObject(i);
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("fvalue", f.getString("fvalue"));
                jsonObject.put("formula", f.getString("formula"));
                jsonObject.put("idx", f.getString("idx"));
                jsonArrayFormula.add(jsonObject);

                List<Object> farrList = new ArrayList<>();
                String formula = f.getString("formula");
                Pattern pattern = Pattern.compile("@[a-zA-Z0-9_\u4e00-\u9fa5()??????-]+@");
                Matcher matcher = pattern.matcher(formula);
                while (matcher.find()) {
                    String fieldCN = matcher.group(0).replace("@", "");
                    Map<String, Object> fieldMap = new HashMap<String, Object>();
                    fieldMap.put("userId", paramMap.get("userId"));
                    fieldMap.put("engineId", paramMap.get("engineId"));
                    fieldMap.put("fieldCn", fieldCN);

                    Field field = fieldMapper.findByFieldCn(fieldMap);

                    if (field.getOrigFieldId() == null) {
                        if (origFieldStr.equals("")) {
                            origFieldStr = Long.toString(field.getId());
                        } else {
                            origFieldStr = origFieldStr + "," + field.getId();
                        }
                    } else {
                        if (origFieldStr.equals("")) {
                            origFieldStr = field.getOrigFieldId();
                        } else {
                            origFieldStr = origFieldStr + "," + field.getOrigFieldId();
                        }
                    }
                    usedFieldStr = usedFieldStr + field.getId() + ","; //??????????????????????????????id
                }
//				}
            }

            paramMap.put("formulaShow", JSON.toJSONString(jsonArrayFormula));
            //??????????????????id
            if (!origFieldStr.equals("")) {
                paramMap.put("origFieldId", getUniqueStr(origFieldStr).toString());
            }

            //??????????????????id
            if (!usedFieldStr.equals(",") && !usedFieldStr.equals("")) {
                usedFieldStr = usedFieldStr.substring(0, usedFieldStr.length() - 1);
                paramMap.put("usedFieldId", getUniqueStr(usedFieldStr).toString());
            }

        } else if (paramMap.containsKey("fieldCondList") && !paramMap.get("fieldCondList").equals("")) {
            //????????????????????????????????????????????????
			
			/*
			  fieldContent=[{"fieldContent2":"[{\"fieldId\":\"3\",\"operator\":\">\",\"fieldValue\":\"200\",\"logical\":\"&&\"}
				  							  ,{\"fieldId\":\"11\",\"operator\":\"<\",\"fieldValue\":\"50\"}]","conditionValue":"5","fieldValue":"50"}
						   ,{"fieldContent2":"[{\"fieldId\":\"12\",\"operator\":\"in\",\"fieldValue\":\"z\",\"logical\":\"&&\"}
				  							  ,{\"fieldId\":\"11\",\"operator\":\">\",\"fieldValue\":\"200\",\"logical\":\"&&\"}
				  							  ,{\"fieldId\":\"31\",\"operator\":\">\",\"fieldValue\":\"1000\"}]","conditionValue":"8","fieldValue":"1000"}
						   ,{"fieldContent2":"[{\"fieldId\":\"31\",\"operator\":\">\",\"fieldValue\":\"4000\"}]","conditionValue":"9","fieldValue":"4000"}]
			*/
            String fieldContent = (String) paramMap.get("fieldCondList");
            List<Object> fieldContentList = new ArrayList<>();
            fieldContentList = JSONObject.parseArray(fieldContent);

            String origFieldStr = "";
            String usedFieldStr = "";

            for (int i = 0; i < fieldContentList.size(); i++) {
                JSONObject fc = ((JSONArray) fieldContentList).getJSONObject(i);
                List<Object> farrList = new ArrayList<>();
                if (!fc.getString("fieldSubCond").equals("") && fc.getString("fieldSubCond") != null) {
                    farrList = JSONObject.parseArray(fc.getString("fieldSubCond"));
                    for (int j = 0; j < farrList.size(); j++) {
                        JSONObject ObjField = ((JSONArray) farrList).getJSONObject(j);
                        usedFieldStr = usedFieldStr + ObjField.get("fieldId") + ",";

                        Map<String, Object> fieldMap = new HashMap<String, Object>();
                        fieldMap.put("userId", paramMap.get("userId"));
                        fieldMap.put("engineId", paramMap.get("engineId"));
                        fieldMap.put("id", ObjField.get("fieldId"));
                        Field field = fieldMapper.findByFieldId(fieldMap);

                        if (field.getOrigFieldId() == null) {
                            if (origFieldStr.equals("")) {
                                origFieldStr = Long.toString(field.getId());
                            } else {
                                origFieldStr = origFieldStr + "," + field.getId();
                            }
                        } else {
                            if (origFieldStr.equals("")) {
                                origFieldStr = field.getOrigFieldId();
                            } else {
                                origFieldStr = origFieldStr + "," + field.getOrigFieldId();
                            }
                        }
                    }
                }
            }
            //??????????????????id
            if (!usedFieldStr.equals(",") && !usedFieldStr.equals("")) {
                usedFieldStr = usedFieldStr.substring(0, usedFieldStr.length() - 1);
                paramMap.put("usedFieldId", getUniqueStr(usedFieldStr).toString());
            }
            //??????????????????id
            if (!origFieldStr.equals("")) {
                paramMap.put("origFieldId", getUniqueStr(origFieldStr).toString());
            }
        }

        Long id = Long.valueOf(paramMap.get("userId").toString());
        //????????????id???????????????????????????
        Field oldFieldVo = new Field();
        oldFieldVo = fieldMapper.findByFieldId(paramMap);
        if (!oldFieldVo.getId().equals(null)) {
            fieldMapper.updateField(paramMap);

            fieldCondMapper.deleteFieldCondById(id);

            String fieldContent = (String) paramMap.get("fieldCondList");
            List<FieldCond> fieldCondVoList = new ArrayList<FieldCond>();
            List<Object> condList = new ArrayList<>();
            if (!fieldContent.equals("")) {
                condList = JSONObject.parseArray(fieldContent);
                for (int i = 0; i < condList.size(); i++) {
                    JSONObject cond = ((JSONArray) condList).getJSONObject(i);
                    List<Object> subCondList = new ArrayList<>();
                    subCondList = JSONObject.parseArray(cond.getString("fieldSubCond"));
                    for (int j = 0; j < subCondList.size(); j++) {
                        JSONObject subCond = ((JSONArray) subCondList).getJSONObject(j);
                        FieldCond fieldCondVo = new FieldCond();
                        fieldCondVo.setFieldId(id);
                        fieldCondVo.setConditionValue(cond.getString("conditionValue"));
                        fieldCondVo.setContent(cond.getString("fieldSubCond"));
                        fieldCondVo.setCondFieldId(Long.valueOf(subCond.getString("fieldId")));
                        fieldCondVo.setCondFieldOperator(subCond.getString("operator"));
                        fieldCondVo.setCondFieldValue(subCond.getString("fieldValue"));
                        fieldCondVo.setCondFieldLogical(subCond.getString("logical"));
                        fieldCondVoList.add(fieldCondVo);
                    }
                }
                fieldCondMapper.createFieldCond(fieldCondVoList);
            }
            return true;
        } else
            return false;
    }

    /**
     * ???????????????????????????????????????id????????????????????????????????????
     *
     * @return
     */
    public String getField(String fieldIds, String usedFieldId, String engineId) {

        Map<String, Object> param = new HashMap<String, Object>();
        Long userId = SessionManager.getLoginAccount().getUserId();
        param.put("userId", userId);
        param.put("fieldId", usedFieldId);
        param.put("engineId", engineId);

        fieldIds = "";

        String str = fieldMapper.checkField(param);

        if (str != null && str.length() >= 0) {

            String arrIds[] = str.split(",");
            for (int i = 0; i < arrIds.length; i++) {
                if (fieldIds.equals("")) {
                    fieldIds = getField("", arrIds[i], engineId);
                } else {
                    fieldIds = fieldIds + "," + getField("", arrIds[i], engineId);
                }

            }
        } else {
            return usedFieldId;
        }
        return fieldIds;
    }

    /**
     * ????????????????????????????????????id????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
     *
     * @return
     */
    @Override
    public String getSourceField(String fieldIds, String fieldId) {

        Map<String, Object> paramMap = new HashMap<String, Object>();
        Long userId = SessionManager.getLoginAccount().getUserId();
        paramMap.put("userId", userId);
        paramMap.put("fieldId", fieldId);

        fieldIds = "";

        //String origFieldId = inputParam.get("origFieldId");
        String usedFieldId = fieldMapper.getSourceField(paramMap);

        if (usedFieldId != null && usedFieldId.length() >= 0) {
            //fieldIds = usedFieldId;
            String arrIds[] = usedFieldId.split(",");
            for (int i = 0; i < arrIds.length; i++) {
                if (fieldIds.equals(""))
                    fieldIds = getSourceField("", arrIds[i]);
                else
                    fieldIds = fieldIds + "," + getSourceField("", arrIds[i]);
            }
        } else {
            return fieldId;
        }

        return fieldIds;
    }

    /**
     * ???????????????????????? ?????????????????????????????????????????????
     *
     * @return
     */
    @Override
    public Map<String, Object> checkField(Map<String, Object> paramMap) {

        boolean beUsed = false;

        List<Field> fieldList = new ArrayList<Field>();
        List<ListDb> listDbList = new ArrayList<ListDb>();
        List<Rule> ruleList = new ArrayList<Rule>();
        List<Scorecard> scorecardList = new ArrayList<Scorecard>();

        String fieldIds = "";

        String fieldId = (String) paramMap.get("fieldId");
        String s = getField("", fieldId, (String) paramMap.get("engineId"));

        //??????????????????????????????????????????????????????
        if (!s.equals("") && !s.equals(fieldId)) {
            fieldIds = getUniqueStr(s).toString();
            List<Long> Ids = new ArrayList<Long>();
            Ids = StringUtil.toLongList(fieldIds);
            paramMap.put("Ids", Ids);
            if (!fieldIds.equals("") && fieldIds != null) {
                //????????????????????????b??????true
                fieldList = fieldMapper.findFieldByIdsForCheckField(paramMap);
                if (fieldList.size() > 0)
                    beUsed = true;
            }
            s = fieldId + "," + s; //????????????????????????????????????????????????????????????
        } else {
            s = fieldId;
        }

        fieldIds = getUniqueStr(s).toString();
        List<Long> Ids = new ArrayList<Long>();
        Ids = StringUtil.toLongList(fieldIds);
        paramMap.put("Ids", Ids);

        //???????????????????????????????????????????????????b??????true
        String listDbIdStr = "";
        List<Long> listDbIds = new ArrayList<Long>();
        for (Iterator iterator = Ids.iterator(); iterator.hasNext(); ) {
            Long Id = (Long) iterator.next();
            paramMap.put("fieldId", Id);
            String str = listDbMapper.checkByField(paramMap);
            if (str != null) {
                if (listDbIdStr.equals(""))
                    listDbIdStr = str;
                else
                    listDbIdStr = listDbIdStr + "," + str;
            }

        }
        if (!listDbIdStr.equals("") && !listDbIdStr.equals(",")) {
            String str = getUniqueStr(listDbIdStr).toString();
            listDbIds = StringUtil.toLongList(str);
            paramMap.put("listDbIds", listDbIds);
            listDbList = listDbMapper.findListDbByIds(paramMap);
        }
        if (listDbList.size() > 0)
            beUsed = true;

        //???????????????????????????????????????b??????true
        paramMap.put("fieldIds", Ids);
//        ruleList = ruleMapper.checkByField(paramMap);
//        if (ruleList.size() > 0)
//            beUsed = true;

        //??????????????????-?????????-?????????
//        scorecardList = scorecardMapper.checkByField(paramMap);
//        if (scorecardList.size() > 0)
//            beUsed = true;

        //<?????????>??????????????????-??????????????????????????????-???????????????

        paramMap.put("fieldList", fieldList);
        paramMap.put("listDbList", listDbList);
        paramMap.put("ruleList", ruleList);
        paramMap.put("scorecardList", scorecardList);
        paramMap.put("beUsed", beUsed);

        return paramMap;

    }

    @Override
    public Map<String, Object> updateStatus(Map<String, Object> paramMap) {

        boolean result = false;

        List<Long> Ids = (List<Long>) paramMap.get("Ids");
        paramMap.put("Ids", Ids);

        if (paramMap.containsKey("status") && !paramMap.get("status").equals("1")) {//?????????????????????????????????????????????

            for (Iterator iterator = Ids.iterator(); iterator.hasNext(); ) {
                Long Id = (Long) iterator.next();
                paramMap.put("fieldId", Id.toString());
                checkField(paramMap);
                if ((boolean) paramMap.get("beUsed")) {
                    break; // ?????????????????????????????????????????????
                }
            }

            if (!(boolean) paramMap.get("beUsed")) {
                paramMap.put("Ids", Ids);
                result = fieldUserMapper.updateStatus(paramMap);
            }

        } else if (paramMap.containsKey("listType") && paramMap.get("listType").equals("cabage")
                && paramMap.get("status").equals("1")) {//??????????????????????????????????????????

            result = backEngFieldType(paramMap);

        } else {//???????????????
            result = fieldUserMapper.updateStatus(paramMap);
        }

        paramMap.put("result", result);

        return paramMap;
    }

    @Override
    public List<Field> findByFieldType(Map<String, Object> paramMap) {

        if (!paramMap.containsKey("fType")) {
            return null;
            // throw new ApiException(ErrorCodeEnum.SERVER_ERROR.getVersionCode(), ErrorCodeEnum.SERVER_ERROR.getMessage());
        }
        Integer fType = Integer.valueOf(paramMap.get("fType").toString());

        switch (fType) {
            case 1:
                // paramMap.put("useSql", false);
                // paramMap.put("derivative", false);
                break;
            case 2:
                // paramMap.put("useSql", true);
                break;
            case 3:
                // paramMap.put("derivative", true);
                break;
            case 4:
                // paramMap.put("interface", true);
                break;
            case 5:
                break;
            case 6:
                break;
            default:
                throw new ApiException(ErrorCodeEnum.SERVER_ERROR.getCode(), ErrorCodeEnum.SERVER_ERROR.getMessage());
        }

        return fieldMapper.findByFieldType(paramMap);
    }

    @Override
    public int isExists(Map<String, Object> paramMap) {
        return fieldMapper.isExists(paramMap);
    }

    @Override
    public Field findByFieldId(Map<String, Object> paramMap) {
        return fieldMapper.findByFieldId(paramMap);
    }

    @Override
    public List<Field> findByUser(Map<String, Object> paramMap) {
        return fieldMapper.findByUser(paramMap);
    }

    @Override
    public List<Field> getFieldList(Map<String, Object> paramMap) {
        return fieldMapper.getFieldList(paramMap);
    }

    @Override
    public boolean bindEngineField(Map<String, Object> paramMap) {

        Long userId = SessionManager.getLoginAccount().getUserId();
        Long organId = SessionManager.getLoginAccount().getOrganId();
        paramMap.put("userId", userId);
        paramMap.put("organId", organId);

        //??????????????????id
        String iFieldIds = (String) paramMap.get("fieldIds");
        String oFieldIds = iFieldIds;
        if (iFieldIds != null && iFieldIds.length() >= 0) {
            String arrIds[] = iFieldIds.split(",");
            for (int i = 0; i < arrIds.length; i++) {
                oFieldIds = oFieldIds + "," + getSourceField("", arrIds[i]);
            }
        }
        String strFieldIds = getUniqueStr(oFieldIds).toString();

        //???????????????????????????????????????
        if (!strFieldIds.equals("") && strFieldIds != null) {
            //????????????????????????id
            List<Long> fieldIds = StringUtil.toLongList(strFieldIds);
            paramMap.put("fieldIds", fieldIds);
            fieldUserMapper.batchBindEngineFieldUserRel(paramMap);
        }


        String strFieldTypeIds = fieldMapper.findOrgFieldTypeIdsByIds(paramMap);
        if (!strFieldTypeIds.equals("") && strFieldTypeIds != null) {

            String parentFieldTypeIds = "";
            //?????????????????????id??????id
            if (!strFieldTypeIds.equals("")) {
                strFieldTypeIds = getUniqueStr(strFieldTypeIds).toString();
                String arrIds[] = strFieldTypeIds.split(",");

                for (int i = 0; i < arrIds.length; i++) {
                    if (parentFieldTypeIds.equals("")) {
                        parentFieldTypeIds = getAllParentFieldTypeId("", arrIds[i], "");
                    } else {
                        parentFieldTypeIds = parentFieldTypeIds + "," + getAllParentFieldTypeId("", arrIds[i], "");
                    }
                }
            }

            if (!parentFieldTypeIds.equals("")) {
                strFieldTypeIds = strFieldTypeIds + "," + parentFieldTypeIds;
            }
            List<Long> fieldTypeIds = StringUtil.toLongList(strFieldTypeIds);

            paramMap.put("fieldTypeIds", fieldTypeIds);
            fieldTypeUserMapper.batchBindEngineFieldTypeUserRel(paramMap);
        }

        return true;
    }

    @Override
    public Map<String, Object> importExcel(String url, Map<String, Object> paramMap) {
        Map<String, Object> resultMap = new HashMap<>();

        InputStream is = null;
        Workbook Workbook = null;
        Sheet Sheet;
        try {
            is = new FileInputStream(url);
            Workbook = WorkbookFactory.create(is);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (InvalidFormatException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        List<Field> fieldVoList = new ArrayList<Field>();
        List<String> fieldEnList = new ArrayList<>();
        int sucRows = 0; // ??????????????????
        int failRows = 0; // ??????????????????
        int repeatRows = 0; // ????????????
        int existRows = 0; // ??????????????????

        // ??????????????? Sheet
        for (int numSheet = 0; numSheet < Workbook.getNumberOfSheets(); numSheet++) {
            Sheet = Workbook.getSheetAt(numSheet);
            if (Sheet == null) {
                continue;
            }
            // ????????? Row
            for (int rowNum = 1; rowNum <= Sheet.getLastRowNum(); rowNum++) {
                try {
                    Row Row = Sheet.getRow(rowNum);
                    if (Row == null) {
                        continue;
                    }
                    Field fieldVo = new Field();
                    fieldVo.setAuthor(Long.valueOf(paramMap.get("author").toString()));
                    fieldVo.setIsCommon(Integer.valueOf(paramMap.get("isCommon").toString()));

                    // ??????????????? Cell
                    for (int cellNum = 0; cellNum <= Row.getLastCellNum(); cellNum++) {
                        Cell cell = Row.getCell(cellNum);
                        String cellStr = ExcelUtil.getCellValue(cell).trim();
                        switch (cellNum) { // ??????????????????

                            case 0:
                                fieldVo.setFieldEn(cellStr);
                                break;
                            case 1:
                                fieldVo.setFieldCn(cellStr);
                                break;
                            case 2:
                                paramMap.put("fieldType", cellStr);
                                Long fieldTypeId = fieldTypeMapper.findIdByFieldType(paramMap);
                                if (fieldTypeId != 0)
                                    fieldVo.setFieldTypeId(fieldTypeId);
                                else
                                    fieldVo.setFieldTypeId(new Long(0)); //??????1???????????????????????????????????????????????????
                                break;
                            case 3:
                                Integer valueType = 0;
                                if (cellStr.equals("?????????")) {
                                    valueType = 1;
                                }
                                if (cellStr.equals("?????????")) {
                                    valueType = 2;
                                }
                                if (cellStr.equals("?????????")) {
                                    valueType = 3;
                                }
                                if (cellStr.equals("?????????")) {
                                    valueType = 4;
                                }
                                fieldVo.setValueType(valueType);
                                break;
                            case 4:
                                fieldVo.setValueScope(cellStr);
                                break;
                            case 5:
                                if (ExcelUtil.getCellValue(cell).equals("Y")) {
                                    fieldVo.setIsDerivative(1);
                                } else {
                                    fieldVo.setIsDerivative(0);
                                }
                                break;
                            case 6:
                                if (cellStr.equals("Y")) {
                                    fieldVo.setIsOutput(1);
                                } else if (cellStr.equals("N")) {
                                    fieldVo.setIsOutput(0);
                                }
                                break;
                            case 7://?????????????????????????????????????????????????????????????????????
                                fieldVo.setFormula(cellStr);
                                break;
                            default:
                                break;
                        }
                    }
                    if (fieldVo.getFieldEn() != null) {
                        paramMap.put("fieldEn", fieldVo.getFieldEn());
                        Field OldFieldVo = fieldMapper.findByFieldName(paramMap);
                        if (OldFieldVo != null) {
                            existRows++;
                            // fieldVo.setUserId(OldFieldVo.getUserId());
                            // ????????????????????????????????????????????????????????????
                            // fieldMapper.updateField(paramMap);
                        } else {
                            // ??????????????????
                            if (fieldEnList.contains(fieldVo.getFieldEn())) {
                                repeatRows++;
                            } else {
                                sucRows++;
                                // ?????????list?????????????????????
                                fieldVoList.add(fieldVo);
                                fieldEnList.add(fieldVo.getFieldEn());
                            }
                        }
                    }
                } catch (Exception e) {
                    failRows++;
                    e.printStackTrace();
                }
            }// end for Row
        }// end first sheet
        if (fieldVoList.size() > 0) {
            fieldMapper.batchCreateField(fieldVoList);
            paramMap.put("status", 1);// ?????????????????????????????????
            fieldUserMapper.batchCreateFieldUserRel(paramMap);
        }
        resultMap.put("sucRows", sucRows);
        resultMap.put("failRows", failRows);
        resultMap.put("repeatRows", repeatRows);
        resultMap.put("existRows", existRows);
        return resultMap;
    }

    /**
     * ??????????????????????????????<?????????>??????????????????????????????
     *
     * @return
     */
    public String getAllFieldTypeId(String ids, String pid, String engineId) {

        Map<String, Object> param = new HashMap<String, Object>();
        Long userId = SessionManager.getLoginAccount().getUserId();
        param.put("userId", userId);
        param.put("engineId", engineId);
        param.put("parentId", pid);

        String sid = fieldTypeMapper.findTypeIdByParentId(param);
        if (sid != null && sid.length() > 0) {
            if (ids.equals(""))
                ids = sid;
            else
                ids = ids + "," + sid;

            String arrIds[] = sid.split(",");
            for (int i = 0; i < arrIds.length; i++) {
                String str = getAllFieldTypeId("", arrIds[i], engineId);
                if (!str.equals(""))
                    ids = ids + "," + str;
            }
        }
        return ids;
    }

    /**
     * ??????????????????????????????<?????????>??????????????????????????????
     *
     * @return
     */
    public String getAllParentFieldTypeId(String ids, String id, String engineId) {

        Map<String, Object> param = new HashMap<String, Object>();
        Long userId = SessionManager.getLoginAccount().getUserId();
        param.put("userId", userId);
        if (engineId == null || engineId.equals("")) {
            engineId = null;
        }
        param.put("engineId", engineId);
        param.put("fieldTypeId", id);

        String pid = fieldTypeMapper.findParentIdByTypeId(param);
        String s = "";
        if (!pid.equals("0")) {
            ids = id + "," + getAllParentFieldTypeId("", pid, engineId);
        } else {
            return id;
        }

        return ids;
    }

    /**
     * ???????????????????????????????????????????????????????????????????????????????????????
     *
     * @return
     */
    public boolean backEngFieldType(Map<String, Object> paramMap) {

        Long userId = SessionManager.getLoginAccount().getUserId();
        paramMap.put("userId", userId);

        String basicFieldTypeIds = fieldMapper.findFieldTypeIdsByFieldId(paramMap);

        String strFieldTypeIds = basicFieldTypeIds;

        String arrIds[] = basicFieldTypeIds.split(",");
        for (int i = 0; i < arrIds.length; i++) {
            String str = getAllParentFieldTypeId("", arrIds[i], (String) paramMap.get("engineId"));
            if (!str.equals("")) {
                strFieldTypeIds = strFieldTypeIds + "," + str;
            }
        }

        //???????????????????????????1
        boolean f = fieldUserMapper.backFieldByIds(paramMap);

        //?????????????????????????????????1
        List<Long> fieldTypeIds = StringUtil.toLongList(strFieldTypeIds);
        paramMap.put("fieldTypeIds", fieldTypeIds);
        boolean ft = fieldTypeMapper.backFieldTypeByTypeIds(paramMap);
        //ft ??????????????????true??????????????????????????????????????????????????????????????????????????????????????????false.
        boolean result = false;
        if (f)
            result = true;

        return result;

    }

    @Override
    public int isExistsFieldType(Map<String, Object> paramMap) {
        return fieldTypeMapper.isExists(paramMap);
    }

    /**
     * ????????????????????????
     *
     * @return
     */
    public static String getRandomString(int length) {
        String base = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < length; i++) {
            int number = random.nextInt(base.length());
            sb.append(base.charAt(number));
        }
        return sb.toString();
    }

    /**
     * ????????????????????????????????????
     *
     * @return
     */
    public static int getRandomInt(String minS, String maxS) {

        int min = 0, max = 0;

        if (minS.indexOf(".") >= 0) { // 3.90 | .9
            minS = minS.substring(0, minS.indexOf("."));
        }

        if (maxS.indexOf(".") >= 0) { // 3.90
            maxS = maxS.substring(0, maxS.indexOf("."));
        }

        if (maxS.equals("") && !minS.equals("")) { // (4,) ????????????
            min = Integer.parseInt(minS);
            max = min + 10000;
        } else if (minS.equals("") && !maxS.equals("")) { // (,10) ????????????
            max = Integer.parseInt(maxS);
            min = max - 10000;
        } else if (!minS.equals("") && !maxS.equals("")) {// (4,10) ???????????????
            min = Integer.parseInt(minS);
            max = Integer.parseInt(maxS);
        }

        Random random = new Random();
        int i = random.nextInt(max) % (max - min + 1) + min;

        return i;

    }

    @Override
    public int updateFieldFolder(UpdateFolderParam param) {
        int result = fieldMapper.updateFieldFolder(param);
        return result;
    }

    @Override
    public String getFieldEnById(Long id) {
        return fieldMapper.findFieldNameById(id);
    }

    @Override
    public List<Field> queryByIds(Collection<Long> ids) {
        if (ids == null || ids.size() == 0) {
            return new ArrayList<>();
        }
        return fieldMapper.selectByIds(ids);
    }

    @Override
    public List<Field> queryByEns(Collection<String> ens) {
        if (ens == null || ens.size() == 0) {
            return new ArrayList<>();
        }
        return fieldMapper.selectByEns(ens);
    }

    @Override
    public List<Field> queryByOrganAndCns(Collection<String> cns, Long organId) {
        if (cns == null || cns.size() == 0) {
            return new ArrayList<>();
        }
        return fieldMapper.selectByOrganCns(cns, organId);
    }

    public void sqlFieldCheck(Map map) {
        if (map.containsKey("sqlStatement")) {
            Object sqlStatement = map.get("sqlStatement");
            if (sqlStatement != null && !"".equals(sqlStatement)) {
                String param = sqlStatement.toString().toUpperCase();
                for (String match : KEY_WORDS) {
                    if (param.contains(match)) {
                        throw new ApiException(ErrorCodeEnum.SQL_FIELD_HAVE_RISK.getCode(), ErrorCodeEnum.SQL_FIELD_HAVE_RISK.getMessage() + ":" + match);
                    }
                }
            }
        }
    }

    @Override
    public int countFieldByOrganId(Long organId) {
        Map<String, Object> map = new HashMap<>();
        map.put("organId", organId);
        int result = fieldUserMapper.countFieldByOrganId(map);
        return result;
    }

    @Override
    public List<Map<String, Object>> countFieldGroupByType(Long organId) {
        Map<String, Object> map = new HashMap<>();
        map.put("organId", organId);
        List<Map<String, Object>> result = fieldUserMapper.countFieldGroupByType(map);
        return result;
    }

    @Override
    public Field queryById(Long id) {
        Field field = null;
        if(Constants.switchFlag.ON.equals(cacheSwitch)){
            String key = RedisUtils.getPrimaryKey(TableEnum.T_FIELD, id);
            field = redisManager.getByPrimaryKey(key, Field.class);
        } else {
            field = fieldMapper.selectById(id);
        }
        return field;
    }

    @Override
    public List<Field> findFieldByIdsbyorganId(Long organId, List<Long> ids) {
        List<Field> fieldList = null;
        if(Constants.switchFlag.ON.equals(cacheSwitch)){
            List<String> keys = RedisUtils.getPrimaryKey(TableEnum.T_FIELD, ids);
            fieldList = redisManager.hgetAllBatchByPrimaryKeys(keys, Field.class);
        } else {
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("organId", organId);
            paramMap.put("Ids", ids);
            fieldList = fieldMapper.findFieldByIdsbyorganId(paramMap);
        }
        return fieldList;
    }

    @Override
    public List<Field> selectFieldListByEns(List<String> fieldEnList) {
        List<Field> fieldList = null;
        if(Constants.switchFlag.ON.equals(cacheSwitch)){
            Long organId = RunnerSessionManager.getSession().getOrganId();
            List<String> keys = fieldEnList.stream().map(item -> {
                String fieldEnStr = Constants.fieldName.fieldEn + ":" + organId + ":" + item;
                String fieldEnKey = RedisUtils.getPrimaryKey(TableEnum.T_FIELD, fieldEnStr);
                return fieldEnKey;
            }).collect(Collectors.toList());

            fieldList = redisManager.hgetAllBatchByPrimaryKeys(keys, Field.class);

        } else {
            fieldList = fieldMapper.selectFieldListByEns(fieldEnList);
        }
        return fieldList;
    }

    @Override
    public Field findByFieldEnbyorganId(Long organId, String fieldEn) {
        Field field = null;
        if(Constants.switchFlag.ON.equals(cacheSwitch)){
            String fieldEnStr = Constants.fieldName.fieldEn + ":" + organId + ":" + fieldEn;
            String fieldEnKey = RedisUtils.getPrimaryKey(TableEnum.T_FIELD, fieldEnStr);
            field = redisManager.getByPrimaryKey(fieldEnKey, Field.class);
            // todo ????????????status = 1??????
        } else {
            Map<String, Object> paramMap = new HashMap<String, Object>();
            paramMap.put("organId", organId);
            paramMap.put("fieldEn", fieldEn);
            field = fieldMapper.findByFieldEnbyorganId(paramMap);
        }
        return field;
    }

    @Override
    public Field findByFieldCnbyorganId(Long organId, String fieldCn) {
        Field field = null;
        if(Constants.switchFlag.ON.equals(cacheSwitch)){
            String fieldCnStr = Constants.fieldName.fieldCn + ":" + organId + ":" + fieldCn;
            String fieldCnKey = RedisUtils.getPrimaryKey(TableEnum.T_FIELD, fieldCnStr);
            field = redisManager.getByPrimaryKey(fieldCnKey, Field.class);
            // todo ????????????status = 1??????
        } else {
            Map<String, Object> paramMap = new HashMap<String, Object>();
            paramMap.put("organId", organId);
            paramMap.put("fieldCn", fieldCn);
            field = fieldMapper.findByFieldCnbyorganId(paramMap);
        }
        return field;
    }
}
