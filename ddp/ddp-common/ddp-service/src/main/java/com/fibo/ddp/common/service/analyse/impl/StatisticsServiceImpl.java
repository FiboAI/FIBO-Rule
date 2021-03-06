package com.fibo.ddp.common.service.analyse.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fibo.ddp.common.dao.analyse.AnalyseEngineCallMapper;
import com.fibo.ddp.common.dao.enginex.risk.EngineMapper;
import com.fibo.ddp.common.dao.enginex.risk.EngineVersionMapper;
import com.fibo.ddp.common.dao.monitor.decisionflow.TMonitorEngineMapper;
import com.fibo.ddp.common.dao.monitor.decisionflow.TMonitorNodeMapper;
import com.fibo.ddp.common.dao.monitor.decisionflow.TMonitorStrategyMapper;
import com.fibo.ddp.common.model.analyse.AnalyseEngineSummary;
import com.fibo.ddp.common.model.enginex.risk.Engine;
import com.fibo.ddp.common.model.enginex.risk.EngineVersion;
import com.fibo.ddp.common.model.monitor.decisionflow.TMonitorEngine;
import com.fibo.ddp.common.model.monitor.decisionflow.TMonitorNode;
import com.fibo.ddp.common.service.analyse.AnalyseEngineCallService;
import com.fibo.ddp.common.service.analyse.AnalyseEngineNodeService;
import com.fibo.ddp.common.service.analyse.AnalyseEngineSummaryService;
import com.fibo.ddp.common.service.analyse.StatisticsService;
import com.fibo.ddp.common.utils.constant.AnalyseConst;
import com.spring4all.spring.boot.starter.hbase.api.HbaseTemplate;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.coprocessor.AggregationClient;
import org.apache.hadoop.hbase.client.coprocessor.LongColumnInterpreter;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class StatisticsServiceImpl implements StatisticsService {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private EngineVersionMapper engineVersionMapper;
    @Autowired
    private EngineMapper engineMapper;
    @Autowired
    private AnalyseEngineCallMapper analyseEngineCallMapper;
    @Autowired
    private AnalyseEngineCallService analyseEngineCallService;
    @Autowired
    private AnalyseEngineSummaryService analyseEngineSummaryService;
    @Autowired
    private AnalyseEngineNodeService analyseEngineNodeService;
    @Autowired
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;
    @Autowired
    private TMonitorEngineMapper monitorEngineMapper;
    @Autowired
    private TMonitorNodeMapper monitorNodeMapper;
    @Autowired
    private TMonitorStrategyMapper monitorStrategyMapper;
    @Autowired
    private HbaseTemplate hbaseTemplate;
    private final String MONITOR_DECISION_FLOW = "monitor_decision_flow";
    private final String MONITOR_NODE = "monitor_node";
    private final String BASE_INFO = "base_info";
    private final String ENGINE_VERSION_ID = "engine_version_id";
    private final String NODE_TYPE = "node_type";

    @Override
    public void statisticData() {
//        AnalyseEngineSummaryCountHBase();
        //????????????
        AnalyseEngineSummaryCountMysql();
        return;
    }
    /**
     * ??????????????????????????????
     */
    private void AnalyseEngineSummaryCountHBase() {
        //?????????????????? ??? ??????id ,????????????Id,??????id ???????????????
        List<EngineVersion> engineVersions = engineVersionMapper.selectAll();
        //????????????  ???????????????????????????
        List<AnalyseEngineSummary> engineCalls = new ArrayList<>();
        for (int i = 0; i < engineVersions.size(); i++) {
            EngineVersion engineVersion = engineVersions.get(i);
            Long engineId = engineVersion.getEngineId();
            Long versionId = engineVersion.getVersionId();
            Engine engine1 = engineMapper.selectById(engineId);
            if(engine1==null){
                continue;
            }
            if(i%20==0){
                try {
                    TimeUnit.SECONDS.sleep(Long.valueOf("5"));//?????????
                } catch (InterruptedException e) {
                    logger.info("==============================??????100s");
                }
            }
            //????????????
            Long countNum = rowCount(BASE_INFO,ENGINE_VERSION_ID,MONITOR_DECISION_FLOW,versionId+"","");
            buildModels(engine1,versionId,engineCalls,countNum, AnalyseConst.ENGINE_CALL);
            //????????????
            Long countNumNode = rowCount(BASE_INFO,ENGINE_VERSION_ID,MONITOR_NODE,versionId+"","");
            buildModels(engine1,versionId,engineCalls,countNumNode,AnalyseConst.NODE_HIT);
            //???????????????
            Long scoredNodeNum = rowCount(BASE_INFO,ENGINE_VERSION_ID,MONITOR_NODE,versionId+"",AnalyseConst.SCORECARD);
            buildModels(engine1,versionId,engineCalls,scoredNodeNum,AnalyseConst.SCORECARD);
            //???????????????
            Long decisionTableNum = rowCount(BASE_INFO,ENGINE_VERSION_ID,MONITOR_NODE,versionId+"",AnalyseConst.DECISION_TABLES);
            buildModels(engine1,versionId,engineCalls,decisionTableNum,AnalyseConst.DECISION_TABLES);
            //?????????????????????????????????
            Long ruleNum = rowCount(BASE_INFO,ENGINE_VERSION_ID,MONITOR_NODE,versionId+"",AnalyseConst.RULE_HIT);
            buildModels(engine1,versionId,engineCalls,ruleNum,AnalyseConst.RULE_HIT);
            System.out.println("===========================decisionCallCount:????????? "+versionId+" ?????? "+countNum);
        }
        insertIntoDB(engineCalls);
        logger.info("=======================================>???????????????{}???",engineCalls.size());
//       if(CollectionUtil.isNotNullOrEmpty(engineCalls)){
//           //????????????,??????500??????
//           for (int i = 0; i < engineCalls.size(); i++) {
//               if(i!=0 && (i%500==0)){
//                 insertIntoDB(engineCalls.subList((i/500-1)*500,i-1));
//               }
//               //?????? ?????????????????????
//               if(engineCalls.size()%500>0 && i==engineCalls.size()-1){
//                   //????????????????????????
//                   if(engineCalls.size()/500>0){
//                       insertIntoDB(engineCalls.subList((engineCalls.size()/500-1)*500,engineCalls.size()-1));
//                       //????????????????????????
//                   }else if(engineCalls.size()/500==0){
//                       insertIntoDB(engineCalls.subList(0,engineCalls.size()-1));
//                   }
//               }
//           }
//       }
       return;
    }

    /**
     * ?????????????????? ??????????????????
     * @param engineCalls
     * @param countNumNode
     * @param type
     */
    private void buildModels(Engine engine1,Long versionId,List<AnalyseEngineSummary> engineCalls, Long countNumNode,String type) {
        //????????????????????????
        AnalyseEngineSummary engineSummary = new AnalyseEngineSummary();
        engineSummary.setEngineVersionId(versionId);
        engineSummary.setEngineName(engine1.getName());
        engineSummary.setStatisticsDimension(type);
        engineSummary.setStatisticsCount(countNumNode);
        engineSummary.setOrganId(engine1.getOrganId());
        engineSummary.setCreateTime(new Date());
        engineSummary.setUpdateTime(new Date());
        engineCalls.add(engineSummary);
    }

    public void insertIntoDB( List<AnalyseEngineSummary> engineCalls){
//        CompletableFuture.runAsync(()->{
        analyseEngineSummaryService.saveBatch(engineCalls);

//        },threadPoolTaskExecutor);
    }


    public Configuration getConfiguration() {
        return hbaseTemplate.getConfiguration();
    }

    public long rowCount(String family,String column,String tableName,String versionId,String flag) {
        long rowCount = 0;
        Configuration conf = getConfiguration();
        Scan scan = new Scan();
//        scan.addColumn("base_info".getBytes(),"engine_version_id".getBytes());
        Filter filter1 = new SingleColumnValueFilter(Bytes.toBytes(family), Bytes.toBytes(column), CompareFilter.CompareOp.EQUAL, JSON.toJSONString(versionId).getBytes());
        FilterList filterList = new FilterList();
        if(flag.equals(AnalyseConst.SCORECARD)){
            Filter filter2 = new SingleColumnValueFilter(Bytes.toBytes(family), Bytes.toBytes(NODE_TYPE), CompareFilter.CompareOp.EQUAL, JSON.toJSONString("4").getBytes());
            filterList.addFilter(filter2);
        }
        if(flag.equals(AnalyseConst.DECISION_TABLES)){
            Filter filter = new SingleColumnValueFilter(Bytes.toBytes(family), Bytes.toBytes(NODE_TYPE), CompareFilter.CompareOp.EQUAL, JSON.toJSONString("16").getBytes());
            filterList.addFilter(filter);
        }
        if(flag.equals(AnalyseConst.RULE_HIT)){
            Filter filter = new SingleColumnValueFilter(Bytes.toBytes(family), Bytes.toBytes(NODE_TYPE), CompareFilter.CompareOp.EQUAL, JSON.toJSONString("2").getBytes());
            filterList.addFilter(filter);
        }
        filterList.addFilter(filter1);
        scan.setFilter(filterList);

        //?????????????????????
//        ValueFilter valueFilter = new ValueFilter(CompareFilter.CompareOp.EQUAL,new BinaryComparator(JSON.toJSONString("507").getBytes()));
//        scan.setFilter(valueFilter);
        //??????RPC????????????
        conf.setLong("hbase.rpc.timeout", 600000);
        //??????Scan??????
        conf.setLong("hbase.client.scanner.caching", 1000);
        Configuration configuration = HBaseConfiguration.create(conf);
        AggregationClient aggregationClient = new AggregationClient(configuration);
        try
        {
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            rowCount = aggregationClient.rowCount(TableName.valueOf(tableName), new LongColumnInterpreter(), scan);
            System.out.println("RowCount: " + rowCount);
            stopWatch.stop();
            System.out.println("???????????????" +stopWatch.getTime()/1000 +"s");
        }
        catch (Throwable e)
        {
            logger.info("==========decisionCallCount ????????????",e);
        }

        return rowCount;
    }


    /**
     * ?????????????????? ??????
     */
    private void AnalyseEngineSummaryCountMysql() {
        //?????????????????? ??? ??????id ,????????????Id,??????id ???????????????
        List<EngineVersion> engineVersions = engineVersionMapper.selectAll();
        //????????????  ???????????????????????????
        List<AnalyseEngineSummary> engineCalls = new ArrayList<>();
        for (int i = 0; i < engineVersions.size(); i++) {
            EngineVersion engineVersion = engineVersions.get(i);
            Long engineId = engineVersion.getEngineId();
            Long versionId = engineVersion.getVersionId();
            Engine engine1 = engineMapper.selectById(engineId);
            if(engine1==null){
                continue;
            }
            if(i%20==0){
                try {
                    TimeUnit.SECONDS.sleep(Long.valueOf("5"));//?????????
                } catch (InterruptedException e) {
                    logger.info("==============================??????100s");
                }
            }
            //?????????????????????Mysql ??? t_monitor_engine ???????????????id ??????????????????????????????
            QueryWrapper<TMonitorEngine> monitorEngineQueryWrapper = new QueryWrapper<>();
            monitorEngineQueryWrapper.eq("engine_version_id",versionId);
            Integer countNum = monitorEngineMapper.selectCount(monitorEngineQueryWrapper);
            buildModels(engine1,versionId,engineCalls,Long.valueOf(countNum),AnalyseConst.ENGINE_CALL);
            //????????????
            QueryWrapper<TMonitorNode> monitorNodeQueryWrapper = new QueryWrapper<>();
            monitorNodeQueryWrapper.eq("engine_version_id",versionId);
            Integer countNumNode = monitorNodeMapper.selectCount(monitorNodeQueryWrapper);
            buildModels(engine1,versionId,engineCalls,Long.valueOf(countNumNode),AnalyseConst.NODE_HIT);
            //???????????????
            QueryWrapper<TMonitorNode> monitorNodeQueryWrapper1 = new QueryWrapper<>();
            monitorNodeQueryWrapper1.eq("engine_version_id",versionId);
            monitorNodeQueryWrapper1.eq("node_type",4);
            Integer scoredNodeNum = monitorNodeMapper.selectCount(monitorNodeQueryWrapper1);
            buildModels(engine1,versionId,engineCalls,Long.valueOf(scoredNodeNum),AnalyseConst.SCORECARD);
            //???????????????
            QueryWrapper<TMonitorNode> monitorNodeQueryWrapper2 = new QueryWrapper<>();
            monitorNodeQueryWrapper2.eq("engine_version_id",versionId);
            monitorNodeQueryWrapper2.eq("node_type",16);
            Integer decisionTableNum = monitorNodeMapper.selectCount(monitorNodeQueryWrapper2);
            buildModels(engine1,versionId,engineCalls,Long.valueOf(decisionTableNum),AnalyseConst.DECISION_TABLES);
            //?????????????????????????????????
            QueryWrapper<TMonitorNode> monitorNodeQueryWrapper3 = new QueryWrapper<>();
            monitorNodeQueryWrapper3.eq("engine_version_id",versionId);
            monitorNodeQueryWrapper3.eq("node_type",2);
            Integer ruleNum = monitorNodeMapper.selectCount(monitorNodeQueryWrapper3);
            buildModels(engine1,versionId,engineCalls,Long.valueOf(ruleNum),AnalyseConst.RULE_HIT);
            System.out.println("===========================decisionCallCount:????????? "+versionId+" ?????? "+countNum);
        }
        insertIntoDB(engineCalls);
        logger.info("=======================================>???????????????{}???",engineCalls.size());
//       if(CollectionUtil.isNotNullOrEmpty(engineCalls)){
//           //????????????,??????500??????
//           for (int i = 0; i < engineCalls.size(); i++) {
//               if(i!=0 && (i%500==0)){
//                 insertIntoDB(engineCalls.subList((i/500-1)*500,i-1));
//               }
//               //?????? ?????????????????????
//               if(engineCalls.size()%500>0 && i==engineCalls.size()-1){
//                   //????????????????????????
//                   if(engineCalls.size()/500>0){
//                       insertIntoDB(engineCalls.subList((engineCalls.size()/500-1)*500,engineCalls.size()-1));
//                       //????????????????????????
//                   }else if(engineCalls.size()/500==0){
//                       insertIntoDB(engineCalls.subList(0,engineCalls.size()-1));
//                   }
//               }
//           }
//       }
        return;
    }

}
