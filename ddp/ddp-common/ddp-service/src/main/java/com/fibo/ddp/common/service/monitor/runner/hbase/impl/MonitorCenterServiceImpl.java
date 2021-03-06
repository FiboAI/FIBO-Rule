package com.fibo.ddp.common.service.monitor.runner.hbase.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.fibo.ddp.common.dao.enginex.risk.EngineResultSetMapper;
import com.fibo.ddp.common.model.enginex.risk.Engine;
import com.fibo.ddp.common.model.enginex.risk.EngineNode;
import com.fibo.ddp.common.model.enginex.risk.EngineVersion;
import com.fibo.ddp.common.model.monitor.decisionflow.MonitorDecisionFlow;
import com.fibo.ddp.common.model.monitor.decisionflow.MonitorNode;
import com.fibo.ddp.common.service.monitor.runner.IMonitorCenterService;
import com.fibo.ddp.common.service.monitor.runner.hbase.node.impl.MonitorBlackOrWhiteNode;
import com.fibo.ddp.common.service.monitor.runner.hbase.node.impl.MonitorDecisionTablesNode;
import com.fibo.ddp.common.service.monitor.runner.hbase.node.impl.MonitorRuleSetNode;
import com.fibo.ddp.common.service.monitor.runner.hbase.node.impl.MonitorScorecardNode;
import com.fibo.ddp.common.utils.constant.monitor.MonitorStorageType;
import com.fibo.ddp.common.utils.constant.monitor.RowKeyUtil;
import org.apache.commons.lang3.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service("monitorCenterServiceImpl")
public class MonitorCenterServiceImpl implements IMonitorCenterService {

    private static final Logger logger = LoggerFactory.getLogger(MonitorCenterServiceImpl.class);

    @Autowired
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;
    @Autowired
    private IMonitorDecisionFlowImpl iMonitorDecisionFlow;
    @Autowired
    private IMonitorNodeImpl iMonitorNode;
    @Autowired
    private MonitorScorecardNode monitorScorecardNode;
    @Autowired
    private MonitorRuleSetNode monitorRuleSetNode;
    @Autowired
    private MonitorBlackOrWhiteNode monitorBlackOrWhiteNode;
    @Autowired
    private MonitorDecisionTablesNode monitorDecisionTablesNode;
    @Resource
    public EngineResultSetMapper resultSetMapper;


    @Override
    public String getStorageType() {
        return MonitorStorageType.HBase;
    }

    @Override
    public void monitorDecisionFlow(Map<String, Object> inputParam, Engine engine, EngineVersion engineVersion, List<EngineNode> engineNodeList, Map<String, Object> outMap, Map<String, Object> paramJson, Integer resultId) {
        CompletableFuture.runAsync(()->{
            try {
                MonitorDecisionFlow.MonitorInfo monitorInfo = new MonitorDecisionFlow.MonitorInfo(
                        inputParam,
                        engineNodeList,
                        (List<String>)outMap.get("executedNodes"));
                MonitorDecisionFlow.BaseInfo baseInfo = new MonitorDecisionFlow.BaseInfo(
                        (String) paramJson.get("pid"),
                        engine.getName(),
                        engineVersion.getVersionId()+"",
                        engineVersion);
                //????????????
                String rowKeyStr = RowKeyUtil.formatLastUpdate(new Date().getTime());
                //??????????????????????????????????????????????????????
                outMap.put("monitorDecisionFlow",rowKeyStr);
                MonitorDecisionFlow monitorDecisionFlow = new MonitorDecisionFlow(rowKeyStr,monitorInfo,baseInfo);
                logger.info("=================================???????????????:{}", JSON.toJSONString(monitorDecisionFlow));
                //todo ????????????Hbase
                iMonitorDecisionFlow.putMonitorDecisionFlowToHbase(monitorDecisionFlow);
                updateResultSet(resultId,rowKeyStr);
                outMap.remove("executedNodes");
                //todo ?????????????????? Hbase?????????????????? ?????????hbase
                monitorNode(engine,engineVersion,outMap);
            } catch (Exception e) {
                logger.info("==============????????????========????????????????????????:{}",e);
            }
        },threadPoolTaskExecutor);
    }
    /**
     *  ??????t_resultset??????hbase_row_key??????
     * @param resultId
     * @param rowKeyStr
     */
    private void updateResultSet(Integer resultId, String rowKeyStr) {
        resultSetMapper.updateResultById(resultId,rowKeyStr);
    }

    /**
     * ?????????????????? Hbase?????????????????? ?????????hbase
     * @param engine
     * @param engineVersion
     * @param outMap
     */
    private void monitorNode(Engine engine, EngineVersion engineVersion, Map<String, Object> outMap) {
        if(outMap.containsKey("monitorNodes")){
            List<MonitorNode> monitorNodeInfoList = (List<MonitorNode>)outMap.get("monitorNodes");
            logger.info("=============================monitorNode:{}", JSONArray.toJSONString(monitorNodeInfoList));
            //????????????list????????????????????????????????????????????????Hbase??????
            monitorNodeInfoList.stream().forEach(item->{
                MonitorNode monitorNode = new MonitorNode();
                //??????????????????
                MonitorNode.MonitorInfo monitorInfo = new MonitorNode.MonitorInfo();
                monitorInfo.setParams(item.getMonitorInfo().getParams());
                monitorInfo.setResult(item.getMonitorInfo().getResult());
                monitorInfo.setSnapshot(item.getMonitorInfo().getSnapshot());
                //??????????????????
                MonitorNode.BaseInfo baseInfo = new MonitorNode.BaseInfo();
                baseInfo.setEngineVersionId(engineVersion.getVersionId()+"");
                baseInfo.setNodeId(item.getBaseInfo().getNodeId());
                baseInfo.setNodeName(item.getBaseInfo().getNodeName());
                baseInfo.setNodeType(item.getBaseInfo().getNodeType());
                baseInfo.setNodeInfo(item.getBaseInfo().getNodeInfo());
                baseInfo.setEngineInfo(engineVersion);
                monitorNode.setMonitorInfo(monitorInfo);
                monitorNode.setBaseInfo(baseInfo);
                //todo ???????????? ????????????????????? hkey+nodeId
                if(outMap.containsKey("monitorDecisionFlow")){
                    String rowKey = outMap.get("monitorDecisionFlow")+"";
                    monitorNode.setRowKey(rowKey+item.getBaseInfo().getNodeId());
                }
                //???????????????????????????????????????????????????????????????????????????????????????
                buildMonitorStrategyModel(monitorNode,outMap);
                iMonitorNode.putMonitorNodeToHbase(monitorNode);
            });
            outMap.remove("monitorDecisionFlow");
        }
    }
    /**
     * ???????????????????????????????????????????????????????????????????????????????????????
     * ?????????Hbase
     * @param monitorNode
     * @Param outMap ???????????????
     */
    public void buildMonitorStrategyModel(MonitorNode monitorNode, Map<String, Object> outMap) {
        String nodeType = monitorNode.getBaseInfo().getNodeType();
        switch(Integer.valueOf(StringEscapeUtils.unescapeJava(nodeType))){
            case 2:
                //??????
                monitorRuleSetNode.createMonitorStrategy(monitorNode,outMap);
                break;
            case 3:
                //??????
                break;
            case 4:
                //?????????
                monitorScorecardNode.createMonitorStrategy(monitorNode,outMap);
                break;
            case 5:
                //?????????
                monitorBlackOrWhiteNode.createMonitorStrategy(monitorNode,outMap);
                break;
            case 6:
                //?????????
                monitorBlackOrWhiteNode.createMonitorStrategy(monitorNode,outMap);
                break;
            case 7:
                //????????????
                break;
            case 9:
                //????????????
                break;
            case 14:
                //?????????
                break;
            case 15:
                //??????
                break;
            case 16:
                //?????????
                monitorDecisionTablesNode.createMonitorStrategy(monitorNode,outMap);
                break;
            default:
                break;
        }
    }

}
