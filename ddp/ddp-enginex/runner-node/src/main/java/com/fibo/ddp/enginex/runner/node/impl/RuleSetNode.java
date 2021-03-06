package com.fibo.ddp.enginex.runner.node.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.fibo.ddp.common.model.datax.datamanage.Field;
import com.fibo.ddp.common.model.enginex.risk.EngineNode;
import com.fibo.ddp.common.model.enginex.runner.ExpressionParam;
import com.fibo.ddp.common.model.strategyx.guiderule.RuleInfo;
import com.fibo.ddp.common.model.strategyx.guiderule.RuleLoopGroupAction;
import com.fibo.ddp.common.model.strategyx.guiderule.vo.RuleConditionVo;
import com.fibo.ddp.common.model.strategyx.guiderule.vo.RuleVersionVo;
import com.fibo.ddp.common.model.strategyx.scriptrule.RuleScriptVersion;
import com.fibo.ddp.common.service.datax.datamanage.FieldService;
import com.fibo.ddp.common.service.datax.runner.CommonService;
import com.fibo.ddp.common.service.datax.runner.ExecuteUtils;
import com.fibo.ddp.common.service.strategyx.guiderule.RuleConditionService;
import com.fibo.ddp.common.service.strategyx.guiderule.RuleService;
import com.fibo.ddp.common.service.strategyx.guiderule.RuleVersionService;
import com.fibo.ddp.common.service.strategyx.scriptrule.RuleScriptVersionService;
import com.fibo.ddp.common.utils.constant.Constants;
import com.fibo.ddp.common.utils.constant.strategyx.RuleConst;
import com.fibo.ddp.common.utils.constant.strategyx.StrategyType;
import com.fibo.ddp.enginex.runner.node.EngineRunnerNode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Service
public class RuleSetNode implements EngineRunnerNode {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private CommonService commonService;
    @Resource
    private RuleService ruleService;
    @Autowired
    private RuleConditionService conditionService;
    @Resource
    private RuleScriptVersionService ruleScriptVersionService;
    @Resource
    private FieldService fieldService;
    @Autowired
    private RuleVersionService versionService;
    @Autowired
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    @Override
    public void getNodeField(EngineNode engineNode, Map<String, Object> inputParam) {
        logger.info("start?????????????????????????????????RuleSetNode.getNodeField engineNode:{},inputParam:{}", JSONObject.toJSONString(engineNode), JSONObject.toJSONString(inputParam));
        JSONObject nodeJson = JSONObject.parseObject(engineNode.getNodeJson());
        List<Long> ids = new ArrayList<>();
        List<Long> versionIds = new ArrayList<>(); // ???????????????
        List<Long> scriptVersionIds = new ArrayList<>(); // ???????????????

        JSONArray jsonArray = null;
        int groupType = nodeJson.getInteger("groupType");
        if (groupType == Constants.ruleNode.MUTEXGROUP) {
            jsonArray = nodeJson.getJSONObject("mutexGroup").getJSONArray("rules");
        } else {
            jsonArray = nodeJson.getJSONObject("executeGroup").getJSONArray("rules");
        }

        for (int i = 0; i < jsonArray.size(); i++) {
            JSONObject ruleObj = jsonArray.getJSONObject(i);
            Long versionId = ruleObj.getLong("ruleVersionId");
            Long difficulty = ruleObj.getLong("difficulty");
            if (difficulty != null && difficulty == 3) {
                scriptVersionIds.add(versionId); // ???????????????
            } else if (versionId != null) {
                versionIds.add(versionId); // ????????????
            }
        }

        //????????????en
        List<String> fieldEnList = new ArrayList<>();
        if (!versionIds.isEmpty()) {
            fieldEnList.addAll(conditionService.queryFieldEnByVersionIds(versionIds));
        }
        if (!scriptVersionIds.isEmpty()) {
            fieldEnList.addAll(ruleScriptVersionService.queryFieldEnByVersionIds(scriptVersionIds));
        }

        //?????????????????????????????????????????????
        fieldEnList = fieldEnList.stream().distinct().filter(f -> f != null && !f.contains(".") && !f.contains("%")).collect(Collectors.toList());
        if (fieldEnList != null && !fieldEnList.isEmpty()) {
            List<Field> fieldList = fieldService.selectFieldListByEns(fieldEnList);
            for (Field field : fieldList) {
                ids.add(field.getId());
            }
        }

        if (!ids.isEmpty()) {
            commonService.getFieldByIds(ids, inputParam);
        }
    }

    @Override
    public void runNode(EngineNode engineNode, Map<String, Object> inputParam, Map<String, Object> outMap) {
        JSONObject nodeJson = JSONObject.parseObject(engineNode.getNodeJson());
        //????????????--????????????????????????
        if (engineNode != null && engineNode.getSnapshot() != null) {
            outMap.put("nodeSnapshot", engineNode.getSnapshot());
        }
        JSONObject nodeInfo = new JSONObject();
        nodeInfo.put("engineNode", engineNode);
        nodeInfo.put("nodeId", engineNode.getNodeId());
        nodeInfo.put("nodeName", engineNode.getNodeName());
        nodeInfo.put("nodeType", engineNode.getNodeType());
        outMap.put("nodeInfo", nodeInfo);
        int groupType = nodeJson.getInteger("groupType") == null ? Constants.ruleNode.EXECUTEGROUP : nodeJson.getInteger("groupType");
        CopyOnWriteArrayList<Map> ruleResultList = new CopyOnWriteArrayList<>();// ????????????????????????
        List<RuleInfo> ruleHitList = new ArrayList<>(); // ?????????????????????

        // ?????????(??????)
        if (groupType == Constants.ruleNode.MUTEXGROUP) {
            JSONArray jsonArray = nodeJson.getJSONObject("mutexGroup").getJSONArray("rules");
            List<RuleInfo> ruleInfoList = getRuleFromJsonArray(jsonArray);
            //????????????--???????????????????????????????????????
            recordStrategySnopshot(ruleInfoList, outMap);
            ruleHitList = serialRule(inputParam, outMap, ruleInfoList, ruleResultList);
        }
        // ?????????(??????)
        else if (groupType == Constants.ruleNode.EXECUTEGROUP) {
            JSONArray jsonArray = nodeJson.getJSONObject("executeGroup").getJSONArray("rules");
            List<RuleInfo> ruleInfoList = getRuleFromJsonArray(jsonArray);
            //????????????--???????????????????????????????????????
            recordStrategySnopshot(ruleInfoList, outMap);
            ruleHitList = parallelRule(inputParam, outMap, ruleInfoList, ruleResultList);
        }

        // ??????????????????
        terminalCondition(engineNode, nodeJson, outMap, ruleHitList);

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("nodeId", engineNode.getNodeId());
        jsonObject.put("nodeName", engineNode.getNodeName());
        jsonObject.put("ruleResultList", ruleResultList);

        if (outMap.containsKey("ruleJson")) {
            JSONArray resultJson = (JSONArray) outMap.get("ruleJson");
            resultJson.add(jsonObject);
        } else {
            JSONArray resultJson = new JSONArray();
            resultJson.add(jsonObject);
            outMap.put("ruleJson", resultJson);
        }
        int hitSize = 0;
        double scoreSum = 0d;
        for (Map map : ruleResultList) {
            Object ruleScore = map.get("ruleScore");
            Object ruleResult = map.get("ruleResult");
            if (null != ruleResult && "??????".equals(ruleResult)) {
                hitSize++;
                if (null != ruleScore) {
                    try {
                        scoreSum += Double.valueOf(ruleScore.toString());
                    } catch (Exception e) {
                        continue;
                    }
                }
            }
        }
        String hitKey = "" + engineNode.getNodeType() + "_" + engineNode.getNodeId() + "_size";
        String scoreKey = "" + engineNode.getNodeType() + "_" + engineNode.getNodeId() + "_score";
        inputParam.put(hitKey, hitSize);
        inputParam.put(scoreKey, scoreSum);
        //????????????==???????????????????????????
        //??????????????????????????????????????????????????????,???????????????????????? ?????????????????????
        JSONObject nodeResult = new JSONObject();
        nodeResult.put("ruleResultList", ruleResultList);
        nodeResult.put("hitNum", hitSize);
        nodeResult.put("scoreTotal", scoreSum);
        outMap.put("nodeResult", nodeResult);
    }

    /**
     * ????????????--??????????????????????????????
     *
     * @param ruleInfoList
     * @param outMap
     */
    private void recordStrategySnopshot(List<RuleInfo> ruleInfoList, Map<String, Object> outMap) {
        JSONArray jsonObject = new JSONArray();
        ruleInfoList.stream().forEach(ruleInfo -> {
            logger.info("===========================????????????????????????????????????==============??????id:{}=====:{}",ruleInfo.getVersion().getId(),ruleInfo.getVersion().getSnapshot());
            if (ruleInfo.getVersion().getSnapshot() != null) {
                jsonObject.add(ruleInfo.getVersion().getSnapshot());

            }
        });
        JSONObject jsonObject1 = new JSONObject();
        jsonObject1.put("snopshot", jsonObject);
        logger.info("===========================????????????????????????????????????:{}",jsonObject1);
        outMap.put("strategySnopshot", jsonObject1);
    }

    /**
     * ??????????????????
     *
     * @param inputParam
     * @param outMap
     * @param ruleInfoList
     * @param ruleResultList
     * @return
     */
    private List<RuleInfo> serialRule(Map<String, Object> inputParam, Map<String, Object> outMap, List<RuleInfo> ruleInfoList, CopyOnWriteArrayList<Map> ruleResultList) {
        logger.info("????????????--??????????????????" + "map:" + JSONObject.toJSONString(inputParam));
        List<RuleInfo> resultList = new ArrayList<>();
        for (int i = 0; i < ruleInfoList.size(); i++) {
            RuleInfo rule = ruleInfoList.get(i);
            boolean hitFlag = executeByDifficulty(inputParam, outMap, rule, ruleResultList);
            if (hitFlag) {
                resultList.add(rule);
                break;
            }
        }
        return resultList;
    }


    /**
     * ??????????????????
     *
     * @param inputParam
     * @param outMap
     * @param ruleInfoList
     * @param ruleResultList
     * @return
     */
    private List<RuleInfo> parallelRule(Map<String, Object> inputParam, Map<String, Object> outMap, List<RuleInfo> ruleInfoList, CopyOnWriteArrayList<Map> ruleResultList) {
        logger.info("????????????--??????????????????" + "map:" + JSONObject.toJSONString(inputParam));
        List<RuleInfo> resultList = new ArrayList<>();
        List<CompletableFuture<RuleInfo>> futureList = new ArrayList<>();
        for (int i = 0; i < ruleInfoList.size(); i++) {
            final int index = i;
            CompletableFuture<RuleInfo> future = CompletableFuture.supplyAsync(() -> {
                RuleInfo rule = ruleInfoList.get(index);
                boolean hitFlag = executeByDifficulty(inputParam, outMap, rule, ruleResultList);
                if (hitFlag) {
                    return rule;
                } else {
                    return null;
                }
            }, threadPoolTaskExecutor);

            futureList.add(future);
        }

        for (CompletableFuture<RuleInfo> future : futureList) {
            try {
                RuleInfo rule = future.get();
                if (rule != null) {
                    resultList.add(rule);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }

        return resultList;
    }

    /**
     * ??????????????????????????????
     *
     * @param inputParam
     * @param outMap
     * @param rule
     * @param ruleResultList
     * @return
     */
    private boolean executeByDifficulty(Map<String, Object> inputParam, Map<String, Object> outMap, RuleInfo rule, CopyOnWriteArrayList<Map> ruleResultList) {
        boolean hitFlag = false;
        if (rule.getDifficulty() == 2) {
            hitFlag = executeComplexRule(inputParam, outMap, rule, ruleResultList);
        } else if (rule.getDifficulty() == 3) {
            hitFlag = executeScriptRule(inputParam, outMap, rule, ruleResultList);
        }
        return hitFlag;
    }

    /**
     * ??????????????????
     *
     * @param input
     * @param output
     * @param rule
     * @param ruleResultList
     * @return
     */
    public boolean executeComplexRule(Map<String, Object> input, Map<String, Object> output, RuleInfo rule, CopyOnWriteArrayList<Map> ruleResultList) {
        boolean hitFlag = false;
        //????????????????????????????????????
//        RuleVo rule = ruleService.queryByVersionId(ruleId);
//        Long versionId = rule.getVersionId();
//        if (versionId==null){
//            return false;
//        }
//        RuleVersionVo ruleVersion = versionService.queryByVersionId(versionId);
        RuleVersionVo ruleVersion = rule.getVersion();
        if (ruleVersion == null) {
            return false;
        }

        //??????????????????????????????
        Map<String, Object> ruleMap = new HashMap<>();
        ruleMap.put("ruleId", rule.getId());
        ruleMap.put("ruleVersionId",ruleVersion.getId());
        ruleMap.put("ruleCode", rule.getCode());
        ruleMap.put("ruleName", rule.getName());
        ruleMap.put("versionCode", ruleVersion.getVersionCode());
        ruleMap.put("versionDesc", ruleVersion.getDescription());
        ruleMap.put("desc", rule.getDescription());
        ruleMap.put("ruleResult", "?????????");

        //???????????????????????????condition?????????
        RuleConditionVo ruleCondition = ruleVersion.getRuleConditionVo();
        //??????????????????????????????????????????????????????????????????condition????????????????????????
        Map<String, Object> temp = JSON.parseObject(JSON.toJSONString(input), Map.class);
        boolean result = this.executeRuleCondition(temp, output, ruleCondition);
        String resultFieldEn = ruleVersion.getResultFieldEn();
        if (resultFieldEn == null || "".equals(resultFieldEn)) {
            resultFieldEn = "rule_2_"+rule.getId()+"_"+ruleVersion.getId()+"_hitResult";
        }
        String scoreFieldEn = ruleVersion.getScoreFieldEn();
        if (StringUtils.isBlank(scoreFieldEn)){
            scoreFieldEn = "rule_2_"+rule.getId()+"_"+ruleVersion.getId()+"_score";
        }
        input.put(resultFieldEn, "?????????");
        //??????????????????????????????????????????????????????
        List<JSONObject> fieldList = new ArrayList<>();
        JSONObject resultJson = new JSONObject();
        if (result) {
            ruleMap.put("ruleResult", "??????");
            ruleMap.put("ruleScore", rule.getScore());
            JSONObject scoreJson = new JSONObject();
            resultJson.put(resultFieldEn, "??????");
            fieldList.add(resultJson);
//            if (StringUtils.isNotBlank(ruleVersion.getScoreFieldEn())) {
                scoreJson.put(scoreFieldEn, ruleVersion.getScore());
                fieldList.add(scoreJson);
                input.put(scoreFieldEn, ruleVersion.getScore());
//            }
            input.put(resultFieldEn, "??????");
            //????????????????????????????????????
            fieldList.addAll(ruleService.setComplexRuleOutput(ruleVersion.getId(), temp, input, StrategyType.OutType.SUCCESS_OUT));
            ruleMap.put("fieldList", fieldList);
            hitFlag = true;
        } else {
            resultJson.put(resultFieldEn, "?????????");
            ruleMap.put("ruleScore", 0);
            input.put(scoreFieldEn,0);
            fieldList.add(resultJson);
            fieldList.addAll(ruleService.setComplexRuleOutput(ruleVersion.getId(), temp, input, StrategyType.OutType.FAIL_OUT));
            ruleMap.put("fieldList", fieldList);
        }
        ruleResultList.add(ruleMap);
        return hitFlag;
    }

    //?????????????????????
    private boolean executeRuleCondition(Map<String, Object> input, Map<String, Object> output, RuleConditionVo ruleCondition) {
        Integer conditionType = ruleCondition.getConditionType();
        boolean result = false;
        switch (conditionType) {
            //?????????????????? &&???||
            case RuleConst.RELATION_CONDITION:
                //?????????????????????
            case RuleConst.LOOP_RESULT_CONDITION:
            case RuleConst.CONDITION_RESULT_CONDITION:
                result = executeRelation(input, output, ruleCondition);
                break;
            //?????????????????????
            case RuleConst.EXPRESSION_CONDITION:
                result = executeExpression(input, output, ruleCondition);
                break;
            //?????????????????????
            case RuleConst.LOOP_CONDITION:
                result = executeLoop(input, output, ruleCondition);
                break;
            //??????????????????
            case RuleConst.CONDITION_GROUP_CONDITION:
                result = executeCondGroup(input, output, ruleCondition);
                break;
        }
        return result;
    }

    //???????????????
    private boolean executeCondGroup(Map<String, Object> input, Map<String, Object> output, RuleConditionVo ruleCondition) {
        //???????????????
        List<RuleConditionVo> children = ruleCondition.getChildren();
        //??????????????????
        int hitNum = 0;
        if (children == null) {
            return false;
        }
        //????????????????????????????????????????????????????????????
        for (RuleConditionVo child : children) {
            boolean childResult = executeRuleCondition(input, output, child);
            if (childResult) {
                hitNum++;
            }
        }
        //?????????????????????????????????null???????????????
        RuleConditionVo condGroup = ruleCondition.getCondGroupResultCondition();
        if (condGroup == null) {
            return false;
        }
        //??????????????????????????????????????????????????????
        Map<String, Object> map = new HashMap<>();
        //?????????????????????map????????????????????????
        map.put("hitNum", hitNum);
        return executeRuleCondition(map, output, condGroup);
    }

    //?????????????????? &&???||
    private boolean executeRelation(Map<String, Object> input, Map<String, Object> output, RuleConditionVo ruleCondition) {
        //??????????????????
        String logical = ruleCondition.getLogical();
        //???????????????
        List<RuleConditionVo> children = ruleCondition.getChildren();

        boolean result = false;
        switch (logical) {
            case "||":
                result = false;
                for (RuleConditionVo child : children) {
                    boolean childResult = executeRuleCondition(input, output, child);
                    if (childResult) {
                        return true;
                    }
                }
                break;
            case "&&":
                result = true;
                for (RuleConditionVo child : children) {
                    boolean childResult = executeRuleCondition(input, output, child);
                    if (!childResult) {
                        return false;
                    }
                }
                break;
        }
        return result;
    }

    //?????????????????????
    private boolean executeExpression(Map<String, Object> input, Map<String, Object> output, RuleConditionVo ruleCondition) {
        String executionLogic = ruleCondition.getExecutionLogic();
        boolean result = false;
        ExpressionParam expressionParam = new ExpressionParam();
        //??????????????????????????????????????????
        BeanUtils.copyProperties(ruleCondition, expressionParam);
        result = ExecuteUtils.getExpressionResult(expressionParam, input);
        return result;
    }

    //?????????????????????
    private boolean executeLoop(Map<String, Object> input, Map<String, Object> output, RuleConditionVo ruleCondition) {
        List<RuleConditionVo> children = ruleCondition.getChildren();
        String fieldEn = ruleCondition.getFieldEn();

        //????????????????????????????????????
        String[] split = fieldEn.split("\\.");
        //???map???????????????????????????????????????
        Object obj = ExecuteUtils.getObjFromMap(input, fieldEn);
        List arrayList = new ArrayList();
        if (obj != null) {
            arrayList.addAll(JSON.parseObject(JSON.toJSONString(obj), ArrayList.class));
        }
        //?????????????????????
        if (arrayList.isEmpty()) {
            return false;
        }
        //?????????????????????key
        String currentKey = "%" + split[split.length - 1] + "%";
        for (RuleConditionVo child : children) {
            List<RuleLoopGroupAction> loopGroupActions = child.getLoopGroupActions();
            // ??????for????????????????????????,??????????????????input???
            for (RuleLoopGroupAction loopGroupAction : loopGroupActions) {
                this.initLoopGroupAction(loopGroupAction, input);
            }
        }
        for (Object currentObj : arrayList) {
            //?????????????????????????????????input
            input.put(currentKey, currentObj);
            //??????????????????for????????????????????????
            for (RuleConditionVo child : children) {
                if (executeRuleCondition(input, output, child)) {
                    List<RuleLoopGroupAction> loopGroupActions = child.getLoopGroupActions();
                    // ??????for????????????????????????,??????????????????input???
                    for (RuleLoopGroupAction loopGroupAction : loopGroupActions) {
                        this.saveLoopGroupAction(loopGroupAction, input);
                    }
                }
            }
        }
        //??????for???????????????
        RuleConditionVo loopResultCondition = ruleCondition.getLoopResultCondition();
        boolean result = executeRuleCondition(input, output, loopResultCondition);
        return result;
    }

    //???????????????????????????
    private void saveLoopGroupAction(RuleLoopGroupAction loopGroupAction, Map<String, Object> input) {
        Integer actionType = loopGroupAction.getActionType();
        String actionKey = loopGroupAction.getActionKey();
        String actionValue = loopGroupAction.getActionValue();
        if (actionType == null) {
            return;
        }
        switch (actionType) {
            case RuleConst.LOOP_GROUP_ACTION_TYPE_SUM:
                Integer count = 1;
                if (input.containsKey(actionKey) && StringUtils.isNumeric(ExecuteUtils.getObjFromMap(input, actionKey).toString())) {
                    count = count + Integer.parseInt(ExecuteUtils.getObjFromMap(input, actionKey).toString());
                }
                input.put(actionKey, count);
                break;
            case RuleConst.LOOP_GROUP_ACTION_TYPE_ASSIGNMENT:
                //???????????????
                break;
            case RuleConst.LOOP_GROUP_ACTION_TYPE_OUT_CONST:
                input.put(actionKey, actionValue);
                break;
            case RuleConst.LOOP_GROUP_ACTION_TYPE_OUT_VARIABLE:
                input.put(actionKey, ExecuteUtils.getObjFromMap(input, actionValue));
                break;
        }
    }


    private void initLoopGroupAction(RuleLoopGroupAction loopGroupAction, Map<String, Object> input){
        Integer actionType = loopGroupAction.getActionType();
        String actionKey = loopGroupAction.getActionKey();
        String actionValue = loopGroupAction.getActionValue();
        if (actionType == null) {
            return;
        }
        switch (actionType) {
            case RuleConst.LOOP_GROUP_ACTION_TYPE_SUM:
                input.put(actionKey, 0);
                break;
            case RuleConst.LOOP_GROUP_ACTION_TYPE_ASSIGNMENT:
                //???????????????
                break;
            case RuleConst.LOOP_GROUP_ACTION_TYPE_OUT_CONST:
                input.put(actionKey, "");
                break;
            case RuleConst.LOOP_GROUP_ACTION_TYPE_OUT_VARIABLE:
                input.put(actionKey,new HashSet<>());
                break;
        }
    }

    /**
     * ??????????????????
     *
     * @param engineNode
     * @param inputParam
     * @param outMap
     * @param ruleHitList
     */
    private void terminalCondition(EngineNode engineNode, Map<String, Object> inputParam, Map<String, Object> outMap, List<RuleInfo> ruleHitList) {
        if (StringUtils.isBlank(engineNode.getNodeScript())) {
            return;
        }
        JSONObject nodeScript = JSONObject.parseObject(engineNode.getNodeScript());
        JSONObject terminationInfo = nodeScript.getJSONObject("terminationInfo");
        JSONArray selectedRule = terminationInfo.getJSONArray("selectedRule");
        String conditions = terminationInfo.getString("conditions");
        if (!selectedRule.isEmpty()) {
            if (!selectedRule.isEmpty()) {
                List<JSONObject> selectedRuleList = JSONObject.parseArray(JSONObject.toJSONString(selectedRule), JSONObject.class);
                // ??????????????????????????????????????????
                List<RuleInfo> selectedHitRules = new ArrayList<>();
                for (JSONObject jsonObject : selectedRuleList) {
                    Optional<RuleInfo> rule = ruleHitList.stream().filter(item -> item.getId().equals(jsonObject.getLong("id"))).findFirst();
                    if (rule.isPresent()) {
                        selectedHitRules.add(rule.get());
                    }
                }

                int totalSize = selectedHitRules.size(); // ??????????????????
                double totalScore = selectedHitRules.stream().mapToDouble(RuleInfo::getScore).sum(); // ???????????????
                String sizeKey = engineNode.getNodeType() + "_" + engineNode.getNodeId() + "_terminal_size";
                String scoreKey = engineNode.getNodeType() + "_" + engineNode.getNodeId() + "_terminal_score";
                Map<String, Object> variablesMap = new HashMap<>();
                variablesMap.put(sizeKey, totalSize);
                variablesMap.put(scoreKey, totalScore);

                ExecuteUtils.terminalCondition(engineNode,inputParam,outMap,variablesMap);
            }
        }
    }

    private List<RuleInfo> getRuleFromJsonArray(JSONArray jsonArray) {
        List<Long> ruleIds = new ArrayList<>();
        Map<Long, Long> map = new HashMap<>();
        for (int i = 0; i < jsonArray.size(); i++) {
            JSONObject ruleObj = jsonArray.getJSONObject(i);
            Long versionId = ruleObj.getLong("ruleVersionId");
            Long ruleId = ruleObj.getLong("id");
            if (ruleId != null) {
                ruleIds.add(ruleId);
                if (versionId != null) {
                    map.put(ruleId, versionId);
                }
            }
        }

        List<RuleInfo> ruleInfoList = ruleService.getRuleList(ruleIds);
        for (RuleInfo ruleInfo : ruleInfoList) {
            if (ruleInfo.getDifficulty() == 2 || ruleInfo.getDifficulty() == 3) {
                Long versionId = map.get(ruleInfo.getId());
                ruleInfo.setVersionId(versionId);
                if (versionId != null) {
                    switch (ruleInfo.getDifficulty()) {
                        case 2:
                            RuleVersionVo ruleVersionVo = versionService.queryById(versionId);
                            ruleInfo.setVersion(ruleVersionVo);
                            ruleInfo.setScore(ruleVersionVo.getScore());
                            break;
                        case 3:
                            RuleScriptVersion ruleScriptVersion = ruleScriptVersionService.queryById(versionId);
                            ruleInfo.setScriptVersion(ruleScriptVersion);
                            ruleInfo.setVersion(JSON.parseObject(JSON.toJSONString(ruleScriptVersion), RuleVersionVo.class));
                            ruleInfo.setScore(0);
                            break;
                    }
                } else {
                    ruleInfo.setScore(0);
                }
            }
        }

        return ruleInfoList;
    }

    /**
     * ??????????????????
     *
     * @param inputParam
     * @param outMap
     * @param rule
     * @param ruleResultList
     * @return
     */
    private boolean executeScriptRule(Map<String, Object> inputParam, Map<String, Object> outMap, RuleInfo rule, CopyOnWriteArrayList<Map> ruleResultList) {
        boolean hitFlag = false;
        RuleScriptVersion scriptVersion = rule.getScriptVersion();
        if (RuleConst.ScriptType.GROOVY.equals(rule.getScriptType())&&RuleConst.ScriptType.GROOVY.equals( scriptVersion.getScriptType())) {
            //groovy????????????
            //???????????????????????????
            if (scriptVersion == null || StringUtils.isBlank(scriptVersion.getScriptContent())) {
                return false;
            }
            //??????????????????
            String scriptContent = scriptVersion.getScriptContent();
            //?????????????????????????????????
            Map<String, Object> ruleMap = new HashMap<>();
            ruleMap.put("ruleId", rule.getId());
            ruleMap.put("ruleVersionId",scriptVersion.getId());
            ruleMap.put("ruleCode", rule.getCode());
            ruleMap.put("ruleName", rule.getName());
            ruleMap.put("versionCode", scriptVersion.getVersionCode());
            ruleMap.put("versionDesc", scriptVersion.getDescription());
            ruleMap.put("desc", rule.getDescription());
            ruleMap.put("ruleResult", "?????????");


            String resultFieldEn = "hitResult";
            String resultEn = "rule_"+rule.getDifficulty()+"_"+rule.getId()+"_"+scriptVersion.getId()+"_hitResult";
            String scoreEn = "rule_"+rule.getDifficulty()+"_"+rule.getId()+"_"+scriptVersion.getId()+"_score";
            inputParam.put(resultEn, "?????????");
            inputParam.put(scoreEn,0);
            //??????????????????????????????????????????????????????
            List fieldList = new ArrayList<>();
            JSONObject resultJson = new JSONObject();
            try {
                Object resp = ExecuteUtils.getObjFromScript(inputParam, scriptContent);
                String result = "?????????";
                JSONObject executeResult = null;
                int ruleScore = 0;
                if (resp instanceof HashMap) {
                    Map resultMap = (HashMap) resp;
                    executeResult = JSON.parseObject(JSON.toJSONString(resultMap));
                    ruleScore = executeResult.getIntValue("ruleScore");
                    result = executeResult.getString(resultFieldEn);
                    JSONArray fieldListJson = executeResult.getJSONArray("fieldList");
                    JSONObject updateInputMap = executeResult.getJSONObject("updateInputMap");
                    if (fieldListJson != null) {
                        fieldList = fieldListJson.toJavaList(Object.class);
                        List list = new ArrayList();
                        for (Object o : fieldList) {
                            if (o!=null&& o instanceof Map){
                                Map map = ExecuteUtils.handleGroovyResult((Map) o);
                                list.add(map);
                            }
                        }
                        fieldList = list;
                    }

                    if (executeResult != null) {
                        ruleMap.put("ruleResult", result);
                        ruleMap.put("ruleScore", ruleScore);
                        resultJson.put(resultFieldEn, result);
                        fieldList.add(resultJson);
                        inputParam.put(resultFieldEn, result);
                        //????????????????????????????????????
                        ruleMap.put("fieldList", fieldList);
                    }
                    if ("??????".equals(result)) {
                        hitFlag = true;
                        inputParam.put(resultEn,"??????");
                        inputParam.put(scoreEn,ruleScore);
                    }
                    //????????????
                    if (updateInputMap!=null&&!updateInputMap.isEmpty()){
                        Set<Map.Entry<String, Object>> entries =  ExecuteUtils.handleGroovyResult(updateInputMap).entrySet();
                        for (Map.Entry<String, Object> entry : entries) {
                            inputParam.put(entry.getKey(),entry.getValue());
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                logger.error("??????????????????????????????{}" + e);
            }
            ruleResultList.add(ruleMap);
        }
        return hitFlag;
    }

//    public static void main(String[] args) {
////        HashMap<String, Object> result = new HashMap<>();                   //????????????????????????
////        int ruleScore = 0;                                                  //?????????????????????
////        String hitResult = "?????????";                                        //???????????????????????????????????????????????????
////        HashMap<String, Object> updateInputMap = new HashMap<>();           //?????????????????????map??????map??????????????????????????????????????????,key????????????????????????
////        ArrayList<HashMap<String, Object>> fieldList = new ArrayList<>();   //??????????????????????????????
////        //??????????????????????????????????????????????????????
////
////
////
////        //?????????????????????????????????????????????
////        result.put("hitResult",hitResult);
////        result.put("ruleScore",ruleScore);
////        result.put("fieldList",fieldList);
////        result.put("updateInputMap",updateInputMap);
////        return result;
//    }

}
