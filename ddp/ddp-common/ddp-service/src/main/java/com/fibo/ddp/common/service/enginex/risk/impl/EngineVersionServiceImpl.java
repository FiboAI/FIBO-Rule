package com.fibo.ddp.common.service.enginex.risk.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fibo.ddp.common.dao.canal.TableEnum;
import com.fibo.ddp.common.dao.enginex.risk.EngineNodeMapper;
import com.fibo.ddp.common.dao.enginex.risk.EngineVersionMapper;
import com.fibo.ddp.common.model.approval.Approval;
import com.fibo.ddp.common.model.enginex.risk.EngineNode;
import com.fibo.ddp.common.model.enginex.risk.EngineVersion;
import com.fibo.ddp.common.service.approval.ApprovalConfigService;
import com.fibo.ddp.common.service.approval.ApprovalService;
import com.fibo.ddp.common.service.enginex.risk.EngineVersionService;
import com.fibo.ddp.common.service.redis.RedisManager;
import com.fibo.ddp.common.service.redis.RedisUtils;
import com.fibo.ddp.common.utils.constant.ApprovalConsts;
import com.fibo.ddp.common.utils.constant.Constants;
import com.fibo.ddp.common.utils.constant.enginex.EngineConst;
import com.fibo.ddp.common.utils.constant.enginex.EngineMsg;
import com.fibo.ddp.common.utils.constant.enginex.NodeTypeEnum;
import com.fibo.ddp.common.utils.util.CollectionUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;

@Service
public class EngineVersionServiceImpl implements EngineVersionService {

    @Autowired
    private EngineVersionMapper engineVersionMapper;
    @Autowired
    private EngineNodeMapper engineNodeMapper;
    @Resource
    private ApprovalService approvalService;
    @Resource
    private ApprovalConfigService approvalConfigService;
    @Autowired
    private RedisManager redisManager;
    @Value("${switch.use.cache}")
    private String cacheSwitch;

    @Override
    @Transactional
    public Map<String,Object> applyDeployEngine(Long versionId) {
        boolean isApproval = approvalConfigService.checkApproval(ApprovalConsts.ApprovalType.DECISION_FLOW_VERSION_DEPLOY);
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("versionId", versionId);
        if (!isApproval){
            int count = this.deployEngine(versionId);
            if (count == 1) {
                resultMap.put("status", EngineMsg.STATUS_SUCCESS);
                resultMap.put("msg", EngineMsg.DEPLOY_SUCCESS);
            } else {
                resultMap.put("status", EngineMsg.STATUS_FAILED);
                resultMap.put("msg", EngineMsg.DEPLOY_FAILED);
            }
        }else {
            int b = engineVersionMapper.updateBootState(versionId, EngineConst.BOOT_STATE_DEPLOY_APPLY);
            //TODO???????????????????????????
            Approval approval = new Approval();
            approval.setApplyType(ApprovalConsts.ApprovalType.DECISION_FLOW_VERSION_DEPLOY);
            JSONObject detail = new JSONObject();
            detail.put("engineVersionId",versionId);
            approval.setApplyDetail(JSON.toJSONString(detail));
            JSONObject desc = new JSONObject();
            desc.put("remark","???????????????????????????");
            approval.setApplyDesc(JSON.toJSONString(desc));
            approvalService.addApproval(approval);
            resultMap.put("status", EngineMsg.STATUS_WAIT);
            resultMap.put("msg", EngineMsg.DEPLOY_WAIT);
        }
        return resultMap;
    }

    @Override
    @Transactional
    public boolean applyDeployFail(Long versionId) {
        engineVersionMapper.updateBootState(versionId,EngineConst.BOOT_STATE_UNDEPLOY);
        return true;
    }

    @Override
    @Transactional
    public boolean approvalCallBack(Long versionId, int result) {
        switch (result){
            //??????
            case ApprovalConsts.ApplyStatus
                    .PASS:
                this.deployEngine(versionId);
                break;
            //??????
            case ApprovalConsts.ApplyStatus
                    .DENY:
                this.applyDeployFail(versionId);
                break;
            //??????
            case ApprovalConsts.ApplyStatus
                    .CANCEL:

                break;
            case ApprovalConsts.ApplyStatus
                    .WAIT:

                break;
        }

        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Transactional
    @Override
    public int deployEngine(Long versionId) {
        EngineVersion engineVersion = engineVersionMapper.selectByPrimaryKey(versionId);
        int count = 0;
        if (engineVersion != null) {
            //????????????????????????
            long engineId = engineVersion.getEngineId();
            //????????????????????????????????????,?????????,??????????????????????????????
            engineVersionMapper.undeployVersion(engineId);
            //????????????
            int version = engineVersion.getVersion();
            //?????????????????????
            int subVersion = engineVersion.getSubVersion();
            //??????????????????????????????????????????????????????
            Map map = new HashMap();
            map.put("engineId", engineId);
            map.put("version", version);
            if (subVersion != 0) {
                //?????????????????????????????????
                EngineVersion latestEngineVersion = engineVersionMapper.getLatestEngineVersion(engineVersion);
                //???????????????????????????,????????????????????????
                engineVersion.setVersion(latestEngineVersion.getVersion() + 1);
                engineVersion.setSubVersion(0);
                engineVersion.setBootState(EngineConst.BOOT_STATE_DEPLOY);
                count = engineVersionMapper.updateByPrimaryKeySelective(engineVersion);
                if (count == 1) {
                    engineVersionMapper.cleanSubVersionByVersion(map);
                }
            } else {
                //???????????????????????????,???boot_state?????????1:????????????
                engineVersion.setBootState(EngineConst.BOOT_STATE_DEPLOY);
                count = engineVersionMapper.updateByPrimaryKeySelective(engineVersion);
            }
        }
        return count;
    }

    @Override
    public int undeployEngine(Long versionId) {
        EngineVersion engineVersion = engineVersionMapper.selectByPrimaryKey(versionId);
        int count = 0;
        if (engineVersion != null) {
            engineVersion.setBootState(EngineConst.BOOT_STATE_UNDEPLOY);
            count = engineVersionMapper.updateByPrimaryKeySelective(engineVersion);
        }
        return count;
    }

    // V2
    @Override
    public List<EngineVersion> getEngineVersionListByEngineIdV2(Long engineId) {
        return engineVersionMapper.getEngineVersionListByEngineIdV2(engineId);
    }

    @Override
    public int update(EngineVersion engineVersion) {
        return engineVersionMapper.updateByPrimaryKey(engineVersion);
    }

    @Override
    public EngineVersion getLatestEngineSubVersion(EngineVersion engineVersion) {
        return engineVersionMapper.getLatestEngineSubVersion(engineVersion);
    }

    @Override
    public Long saveEngineVersion(EngineVersion engineVersion, List<EngineNode> nodeList) {
        int count = engineVersionMapper.insertEngineVersionAndReturnId(engineVersion);
        if (count == 1) {
            long versionId = engineVersion.getVersionId();
            //????????????????????????????????????????????????
            for (EngineNode engineNode : nodeList) {
                // ?????????????????????id
                engineNode.setParentId(null);
                engineNode.setVersionId(versionId);
                if (engineNode.getNodeType().intValue() == NodeTypeEnum.POLICY.getValue() || engineNode.getNodeType().intValue() == NodeTypeEnum.NODE_COMPLEXRULE.getValue()) {
                    engineNodeMapper.insertNodeAndReturnId(engineNode);
                } else if (engineNode.getNodeType().intValue() == NodeTypeEnum.SCORECARD.getValue()) {
                    engineNodeMapper.insertNodeAndReturnId(engineNode);
                } else if (engineNode.getNodeType().intValue() == NodeTypeEnum.BLACKLIST.getValue() || engineNode.getNodeType().intValue() == NodeTypeEnum.WHITELIST.getValue()) {
                    //????????????????????????????????????
                    engineNodeMapper.insertNodeAndReturnId(engineNode);
                } else {
                    //???????????????????????????
                    engineNodeMapper.insert(engineNode);
                }
            }
            return versionId;
        }
        return new Long(0);
    }

    @Override
    public EngineVersion selectByPrimaryKey(Long versionId) {
        return engineVersionMapper.selectByPrimaryKey(versionId);
    }

    @Override
    public boolean clear(Long versionId) {
        // ???????????????????????????
        // ???????????????????????????????????????nextNode,
        // ?????????????????????????????????,?????????????????????????????????,
        // ?????????,??????????????????????????????????????????,
        // ?????????,????????????????????????
        EngineNode startNode = null;
        List<Long> knowledges = new ArrayList<>();
        List<Long> blackWhites = new ArrayList<>();
        List<Long> commons = new ArrayList<>();

        // ?????????,????????????????????????
        List<EngineNode> engineNodes = engineNodeMapper.getEngineNodeListByEngineVersionId(versionId);
        if (CollectionUtil.isNotNullOrEmpty(engineNodes)) {
            for (EngineNode engineNode : engineNodes) {
                switch (engineNode.getNodeType()) {
                    case 1:
                        //????????????
                        startNode = engineNode;
                        startNode.setNextNodes("");
                        break;
                    case 2:
                    case 4:
                    case 13:
                        //??????????????????
                        knowledges.add(engineNode.getNodeId());
                        commons.add(engineNode.getNodeId());
                        break;
                    case 5:
                    case 6:
                        //????????????
                        blackWhites.add(engineNode.getNodeId());
                        commons.add(engineNode.getNodeId());
                        break;
                    default:
                        commons.add(engineNode.getNodeId());
                        break;
                }
            }

            //?????????,??????????????????????????????????????????
            if (CollectionUtil.isNotNullOrEmpty(knowledges)) {
                // nodeKnowledgeMapper.deleteKnowledgesBatchByNodeIds(knowledges);
            }
            //?????????,????????????
            if (CollectionUtil.isNotNullOrEmpty(commons)) {
                engineNodeMapper.deleteNodesByNodeIds(commons);
            }
            //?????????,???start?????????nextNode??????
            if (startNode != null) {
                engineNodeMapper.updateNextNodes(startNode);
            }
        } else {
            return false;
        }
        return true;
    }

    @Override
    public List<EngineVersion> getEngineVersionByEngineId(Map<String, Object> paramMap) {
        return engineVersionMapper.getEngineVersionByEngineId(paramMap);
    }

    @Override
    public EngineVersion getRunningVersion(Long engineId) {
        EngineVersion engineVersion = null;
        if(Constants.switchFlag.ON.equals(cacheSwitch)){
            String key = RedisUtils.getForeignKey(TableEnum.T_ENGINE_VERSION, engineId);
            List<EngineVersion> list = redisManager.getByForeignKey(key, EngineVersion.class);
            Optional<EngineVersion> optional = list.stream().filter(item -> item.getBootState() == 1).findFirst();
            if(optional.isPresent()){
                engineVersion = optional.get();
            }
        } else {
            engineVersion = engineVersionMapper.getRunningVersion(engineId);
        }

        return engineVersion;
    }
}
