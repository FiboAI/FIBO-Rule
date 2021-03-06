package com.fibo.ddp.enginex.riskengine.runner.business.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.fibo.ddp.common.dao.enginex.risk.EngineResultSetMapper;
import com.fibo.ddp.common.model.enginex.risk.Engine;
import com.fibo.ddp.common.model.enginex.risk.EngineNode;
import com.fibo.ddp.common.model.enginex.risk.EngineResultSet;
import com.fibo.ddp.common.model.enginex.risk.EngineVersion;
import com.fibo.ddp.common.model.monitor.decisionflow.MonitorNode;
import com.fibo.ddp.common.service.enginex.risk.EngineNodeService;
import com.fibo.ddp.common.service.enginex.risk.EngineService;
import com.fibo.ddp.common.service.enginex.risk.EngineVersionService;
import com.fibo.ddp.common.service.monitor.runner.MonitorCenterFactoryRunner;
import com.fibo.ddp.common.service.monitor.runner.MonitorCommonService;
import com.fibo.ddp.common.service.monitor.runner.hbase.IFeatureRecordService;
import com.fibo.ddp.common.utils.constant.enginex.NodeTypeEnum;
import com.fibo.ddp.common.utils.constant.monitor.MonitorStorageType;
import com.fibo.ddp.enginex.riskengine.runner.business.RiskEngineBusiness;
import com.fibo.ddp.enginex.runner.node.impl.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.web.client.AsyncRestTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RiskEngineBusinessImpl implements RiskEngineBusiness {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    public EngineService engineService;
    @Resource
    public EngineVersionService engineVersionService;
    @Resource
    public EngineNodeService engineNodeService;
    @Resource
    public EngineResultSetMapper resultSetMapper;
    @Resource
    private DecisionTablesNode decisionTablesNode;
    @Resource
    private DecisionTreeNode decisionTreeNode;
    @Autowired
    private DecisionOptionsNode decisionOptionsNode;
    @Autowired
    private ScorecardNode scorecardNode;
    @Autowired
    private RuleSetNode ruleSetNode;
    @Autowired
    private GroupNode groupNode;
    @Autowired
    private ModelNode modelNode;
    @Autowired
    private ChildEngineNode childEngineNode;
    @Autowired
    private BlackOrWhiteNode blackOrWhiteNode;
    @Autowired
    private AggregationNode aggregationNode;
    @Autowired
    private ParallelNode parallelNode;
    @Autowired
    private ChampionChallengeNode championChallengeNode;
    @Autowired
    private RpcNode rpcNode;
    @Autowired
    private SandboxProportionNode sandboxProportionNode;
    @Autowired
    private MonitorCommonService monitorCommonService;
    @Autowired
    private IFeatureRecordService featureRecordService;
    @Autowired
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;
    @Autowired(required = false)
    private AsyncRestTemplate asyncRestTemplate;
    @Value("${monitor.data.storage.type}")
    private String storageType;

    @Override
    public String engineApi(Map<String, Object> paramJson) {
        logger.info("???????????????paramJson: {}", JSONObject.toJSONString(paramJson));
        JSONObject jsonObject = new JSONObject();
        JSONArray resultJson = new JSONArray();
        Map<String, Map<String, Object>> featureMaps = new ConcurrentHashMap<>();
        Long organId = Long.valueOf(paramJson.get("organId").toString());
        Long engineId = Long.valueOf(paramJson.get("engineId").toString());
        //??????????????????
        Engine engine = engineService.getEngineById(engineId);
        //????????????????????????????????????
        EngineVersion engineVersion = engineVersionService.getRunningVersion(engineId);
        if (engineVersion == null) {
            jsonObject.put("status", "0x0004");
            jsonObject.put("msg", "??????????????????????????????????????????");
            jsonObject.put("data", resultJson);
            return jsonObject.toString();
        }

        //????????????????????????????????????
        List<EngineNode> engineNodeList = engineNodeService.getEngineNodeListByVersionId(engineVersion.getVersionId());
        Map<String, EngineNode> engineNodeMap = getEngineNodeListByMap(engineNodeList);
        try {
            //?????????
            Map<String, Object> inputParam = new ConcurrentHashMap<>();
            inputParam.putAll(JSONObject.parseObject(JSONObject.toJSONString(paramJson.get("fields")), Map.class));
            EngineNode engineNode = engineNodeMap.get("ND_START");
            if (null != engineNode && null != engineNode.getNextNodes()) {
                //??????????????????
                Map<String, Object> outMap = new ConcurrentHashMap<>();
                // ???????????????????????????
                featureMaps.put("before", inputParam);
                //??????????????????
                recursionEngineNode(inputParam, engineNodeMap.get(engineNode.getNextNodes()), engineNodeMap, outMap);
                jsonObject.put("status", "0x0000");
                jsonObject.put("msg", "????????????");
                //??????????????????????????????
                featureMaps.put("after", inputParam);
                paramJson.put("versionId", engineNode.getVersionId());
//                featureRecordService.recordAllFeature(featureMaps, engine, paramJson);

                String json = JSONObject.toJSONString(inputParam);
                jsonObject.put("input", JSONObject.parseObject(json));

                EngineResultSet resultSet = new EngineResultSet();
                resultSet.setEngineCode(engine.getCode());
                resultSet.setInput(json);
                resultSet.setEngineId(engine.getId());
                resultSet.setEngineName(engine.getName());
                resultSet.setType(2);
                resultSet.setSubVersion(engineVersion.getSubVersion());
                resultSet.setUid(String.valueOf(paramJson.get("uid")));
                resultSet.setPid(String.valueOf(paramJson.get("pid")));

                // ??????????????????
                if (outMap.containsKey("result")) {
                    resultSet.setResult(outMap.get("result").toString());
                    //??????????????????
                    jsonObject.put("result", outMap.get("result").toString());
                }
                // ?????????????????????
                if (outMap.containsKey("blackJson")) {
                    resultJson.add(new JSONObject().parse(outMap.get("blackJson").toString()));
                }
                // ?????????????????????
                if (outMap.containsKey("whiteJson")) {
                    resultJson.add(new JSONObject().parse(outMap.get("whiteJson").toString()));
                }
                // ?????????????????????
                if (outMap.containsKey("ruleJson")) {
                    JSONObject ruleJson = new JSONObject();
                    ruleJson.put("resultType", 2);
                    ruleJson.put("resultJson", outMap.get("ruleJson"));
                    resultJson.add(ruleJson);
                }
                // ?????????????????????
                if (outMap.containsKey("scoreJson")) {
                    JSONObject ruleJson = new JSONObject();
                    ruleJson.put("resultType", 4);
                    ruleJson.put("resultJson", outMap.get("scoreJson"));
                    resultJson.add(ruleJson);
                }
                // ????????????????????????
                if (outMap.containsKey("decisionJson")) {
                    JSONObject ruleJson = new JSONObject();
                    ruleJson.put("resultType", 9);
                    ruleJson.put("resultJson", outMap.get("decisionJson"));
                    resultJson.add(ruleJson);
                }
                // ?????????????????????
                if (outMap.containsKey("childEngineJson")) {
                    JSONObject ruleJson = new JSONObject();
                    ruleJson.put("resultType", 14);
                    ruleJson.put("resultJson", outMap.get("childEngineJson"));
                    resultJson.add(ruleJson);
                }
                // ??????????????????
                if (outMap.containsKey("modelJson")) {
                    JSONObject ruleJson = new JSONObject();
                    ruleJson.put("resultType", 15);
                    ruleJson.put("resultJson", outMap.get("modelJson"));
                    resultJson.add(ruleJson);
                }
                // ?????????????????????
                if (outMap.containsKey("decisionTablesJson")) {
                    JSONObject ruleJson = new JSONObject();
                    ruleJson.put("resultType", 16);
                    ruleJson.put("resultJson", outMap.get("decisionTablesJson"));
                    resultJson.add(ruleJson);
                }
                // ?????????????????????
                if (outMap.containsKey("decisionTreeJson")) {
                    JSONObject ruleJson = new JSONObject();
                    ruleJson.put("resultType", 17);
                    ruleJson.put("resultJson", outMap.get("decisionTreeJson"));
                    resultJson.add(ruleJson);
                }
                jsonObject.put("data", resultJson);
                String result = JSONObject.toJSONString(jsonObject);

                JSONObject tmpJsonObject = JSONObject.parseObject(result);
                tmpJsonObject.remove("input");
                resultSet.setOutput(JSONObject.toJSONString(tmpJsonObject));
                resultSetMapper.insertResultSet(resultSet);
                Integer resultId = resultSet.getId();
//                this.monitorDecisionFlow(inputParam, engine, engineVersion, engineNodeList, outMap, paramJson, resultId);
                // ????????????????????????
                decisionCallback(engine.getCallbackUrl(), paramJson, result);
            }
        } catch (Exception e) {
            logger.error("??????????????????", e);
            jsonObject.put("status", "0x0005");
            jsonObject.put("msg", "????????????");
            jsonObject.put("data", resultJson);
            // ????????????
            decisionCallback(engine.getCallbackUrl(), paramJson, "????????????");
        }

        return jsonObject.toString();
    }

    /**
     * ???????????????
     *
     * @param inputParam
     * @param engine
     * @param engineVersion
     * @param engineNodeList
     * @param outMap
     * @param paramJson
     * @param resultId
     */
    private void monitorDecisionFlow(Map<String, Object> inputParam, Engine engine, EngineVersion engineVersion, List<EngineNode> engineNodeList, Map<String, Object> outMap, Map<String, Object> paramJson, Integer resultId) {
        switch (storageType) {
            case MonitorStorageType.Mysql:
                MonitorCenterFactoryRunner.getMonitorCenterServiceImp(MonitorStorageType.Mysql).monitorDecisionFlow(inputParam, engine, engineVersion, engineNodeList, outMap, paramJson, resultId);
                break;
            case MonitorStorageType.HBase:
                MonitorCenterFactoryRunner.getMonitorCenterServiceImp(MonitorStorageType.HBase).monitorDecisionFlow(inputParam, engine, engineVersion, engineNodeList, outMap, paramJson, resultId);
                break;
            default:
                logger.info("??????????????????????????????????????????");
                break;
        }
    }

    /**
     * ??????????????????
     *
     * @param inputParam
     * @param engineNode
     * @param engineNodeMap
     * @param outMap
     */
    private EngineNode recursionEngineNode(Map<String, Object> inputParam, EngineNode engineNode, Map<String, EngineNode> engineNodeMap, Map<String, Object> outMap) {
        logger.info("????????????--" + "inputParam:" + JSONObject.toJSONString(inputParam));

        EngineNode resultNode = null; // ?????????????????????: ??????????????????null?????????????????????????????????

        if (engineNode == null) {
            return null;
        }

        // ???????????????????????????
        getNodeField(engineNode, inputParam);
        // ??????????????????
        runNode(engineNode, inputParam, outMap);

        //??????????????????????????????
        List<String> executedNodeList = new ArrayList<>();
        //????????????????????????????????????????????????????????????(??????id,????????????????????????????????????)
        List<MonitorNode> monitorNodeInfoList = new ArrayList<>();
        if (outMap.containsKey("monitorNodes")) {
            monitorNodeInfoList = (List<MonitorNode>) outMap.get("monitorNodes");
        }
        if (outMap.containsKey("executedNodes")) {
            executedNodeList = (List<String>) outMap.get("executedNodes");
        }
        executedNodeList.add(engineNode.getNodeId() + "");
        // ???????????????????????????
        outMap.put("executedNodes", executedNodeList);
        monitorCommonService.buildMonitorNode(inputParam, engineNode, outMap, monitorNodeInfoList);
        // ???????????????????????????????????? ??????????????????
        outMap.put("monitorNodes", monitorNodeInfoList);

        // ???????????????????????????
        if (StringUtils.isNotBlank(engineNode.getNextNodes())) {
            if (engineNode.getNodeType() == NodeTypeEnum.PARALLEL.getValue()) {
                // ??????????????????
                EngineNode aggregationNode = parallelNode(inputParam, engineNode, engineNodeMap, outMap);
                recursionEngineNode(inputParam, aggregationNode, engineNodeMap, outMap);

            } else {
                // ??????????????????
                EngineNode nextEngineNode = engineNodeMap.get(engineNode.getNextNodes());
                //???????????????map?????????nextNode??????????????????????????????????????????????????????
                if (outMap.containsKey("nextNode")) {
                    nextEngineNode = engineNodeMap.get(outMap.get("nextNode"));
                    outMap.remove("nextNode");
                }

                if (nextEngineNode != null && nextEngineNode.getNodeType() == NodeTypeEnum.AGGREGATION.getValue()) {
                    // ??????????????????????????????????????????????????????????????????????????????
                    resultNode = nextEngineNode;
                } else {
                    resultNode = recursionEngineNode(inputParam, nextEngineNode, engineNodeMap, outMap);
                }
            }
        }

        return resultNode;
    }


    /**
     * ????????????????????????????????????????????????????????????????????????????????????
     *
     * @param inputParam
     * @param engineNode
     * @param engineNodeMap
     * @param outMap
     * @return
     */
    private EngineNode parallelNode(Map<String, Object> inputParam, EngineNode engineNode, Map<String, EngineNode> engineNodeMap, Map<String, Object> outMap) {
        EngineNode aggregationNode = null; // ????????????code
        String[] nextNodeArr = engineNode.getNextNodes().split(",");
        List<CompletableFuture<EngineNode>> futureList = new ArrayList<>();
        for (String nextNodeCode : nextNodeArr) {
            CompletableFuture<EngineNode> future = CompletableFuture.supplyAsync(() -> {
                EngineNode nextEngineNode = engineNodeMap.get(nextNodeCode);
                EngineNode resultNode = recursionEngineNode(inputParam, nextEngineNode, engineNodeMap, outMap);
                return resultNode;
            }, threadPoolTaskExecutor);
            futureList.add(future);
        }

        for (CompletableFuture<EngineNode> future : futureList) {
            try {
                EngineNode result = future.get();
                aggregationNode = result;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return aggregationNode;
    }

    /**
     * ???????????????????????????
     *
     * @param engineNode
     * @param inputParam
     */
    private void getNodeField(EngineNode engineNode, Map<String, Object> inputParam) {
        switch (engineNode.getNodeType()) {
            case 2:
                //??????
                ruleSetNode.getNodeField(engineNode, inputParam);
                break;
            case 3:
                //??????
                groupNode.getNodeField(engineNode, inputParam);
                break;
            case 4:
                //?????????
                scorecardNode.getNodeField(engineNode, inputParam);
                break;
            case 5:
                //?????????
                blackOrWhiteNode.getNodeField(engineNode, inputParam);
                break;
            case 6:
                //?????????
                blackOrWhiteNode.getNodeField(engineNode, inputParam);
                break;
            case 9:
                //????????????
                decisionOptionsNode.getNodeField(engineNode, inputParam);
                break;
            case 14:
                //?????????
                childEngineNode.getNodeField(engineNode, inputParam);
                break;
            case 15:
                //??????
                modelNode.getNodeField(engineNode, inputParam);
                break;
            case 16:
                //?????????
                decisionTablesNode.getNodeField(engineNode, inputParam);
                break;
            case 17:
                //?????????
                decisionTreeNode.getNodeField(engineNode, inputParam);
                break;
            case 18:
                //??????????????????
                rpcNode.getNodeField(engineNode, inputParam);
                break;
            case 19:
                //????????????
                parallelNode.getNodeField(engineNode, inputParam);
                break;
            case 20:
                //????????????
                aggregationNode.getNodeField(engineNode, inputParam);
                break;
            case 21:
                //??????????????????
                championChallengeNode.getNodeField(engineNode, inputParam);
                break;
            default:
                break;
        }
    }

    /**
     * ??????????????????
     *
     * @param engineNode
     * @param inputParam
     * @param outMap
     */
    private void runNode(EngineNode engineNode, Map<String, Object> inputParam, Map<String, Object> outMap) {
        switch (engineNode.getNodeType()) {
            case 2:
                //??????
                ruleSetNode.runNode(engineNode, inputParam, outMap);
                break;
            case 3:
                //??????
                groupNode.runNode(engineNode, inputParam, outMap);
                break;
            case 4:
                //?????????
                scorecardNode.runNode(engineNode, inputParam, outMap);
                break;
            case 5:
                //?????????
                blackOrWhiteNode.runNode(engineNode, inputParam, outMap);
                break;
            case 6:
                //?????????
                blackOrWhiteNode.runNode(engineNode, inputParam, outMap);
                break;
            case 7:
                //????????????
                sandboxProportionNode.runNode(engineNode, inputParam, outMap);
                break;
            case 9:
                //????????????
                decisionOptionsNode.runNode(engineNode, inputParam, outMap);
                break;
            case 14:
                //?????????
                childEngineNode.runNode(engineNode, inputParam, outMap);
                break;
            case 15:
                //??????
                modelNode.runNode(engineNode, inputParam, outMap);
                break;
            case 16:
                //?????????
                decisionTablesNode.runNode(engineNode, inputParam, outMap);
                break;
            case 17:
                //?????????
                decisionTreeNode.runNode(engineNode, inputParam, outMap);
                break;
            case 18:
                //??????????????????
                rpcNode.runNode(engineNode, inputParam, outMap);
                break;
            case 19:
                //????????????
                parallelNode.runNode(engineNode, inputParam, outMap);
                break;
            case 20:
                //????????????
                aggregationNode.runNode(engineNode, inputParam, outMap);
                break;
            case 21:
                //??????????????????
                championChallengeNode.runNode(engineNode, inputParam, outMap);
                break;
            default:
                break;
        }
    }

    /**
     * ??????????????????????????????key??????map
     *
     * @param nodelist ????????????
     * @return map
     * @see
     */
    private Map<String, EngineNode> getEngineNodeListByMap(List<EngineNode> nodelist) {
        Map<String, EngineNode> map = new HashMap<>();
        for (int i = 0; i < nodelist.size(); i++) {
            map.put(nodelist.get(i).getNodeCode(), nodelist.get(i));
        }
        return map;
    }

    /**
     * ??????????????????????????????????????????????????????????????????????????????????????????
     *
     * @param url
     * @param paramJson
     * @param result
     */
    private void decisionCallback(String url, Map<String, Object> paramJson, String result) {
        if (StringUtils.isBlank(url)) {
            return;
        }
        Map<String, String> paramMap = new HashMap<>();
        paramMap.put("paramJson", JSONObject.toJSONString(paramJson));
        paramMap.put("result", result);
        // ???????????????
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        // ???????????????
        JSONObject body = JSONObject.parseObject(JSONObject.toJSONString(paramMap));
        // ????????????????????????
        HttpEntity<JSONObject> httpEntity = new HttpEntity(body, httpHeaders);
        ListenableFuture<ResponseEntity<String>> future = asyncRestTemplate.postForEntity(url, httpEntity, String.class);
        if (future != null) {
            future.addCallback(new ListenableFutureCallback<ResponseEntity<String>>() {
                @Override
                public void onFailure(Throwable throwable) {
                    logger.info("??????????????????????????????", throwable);
                }

                @Override
                public void onSuccess(ResponseEntity<String> stringResponseEntity) {
                    String result = stringResponseEntity.getBody();
                    logger.info("?????????????????????????????????result:{}", result);
                }
            });
        }
    }
}
