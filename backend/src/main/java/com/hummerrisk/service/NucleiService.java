package com.hummerrisk.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.Gson;
import com.hummerrisk.base.domain.*;
import com.hummerrisk.base.mapper.*;
import com.hummerrisk.base.mapper.ext.ExtCloudTaskMapper;
import com.hummerrisk.commons.constants.*;
import com.hummerrisk.commons.exception.HRException;
import com.hummerrisk.commons.utils.*;
import com.hummerrisk.dto.QuartzTaskDTO;
import com.hummerrisk.i18n.Translator;
import com.hummerrisk.proxy.nuclei.NucleiCredential;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.Resource;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static com.alibaba.fastjson.JSON.parseObject;
import static com.alibaba.fastjson.JSON.toJSONString;

/**
 * @author harris
 */
@Service
public class NucleiService {

    @Resource @Lazy
    private CloudTaskMapper cloudTaskMapper;
    @Resource @Lazy
    private CloudTaskItemMapper cloudTaskItemMapper;
    @Resource @Lazy
    private CloudTaskItemLogMapper cloudTaskItemLogMapper;
    @Resource @Lazy
    private CommonThreadPool commonThreadPool;
    @Resource @Lazy
    private CloudTaskItemResourceMapper cloudTaskItemResourceMapper;
    @Resource @Lazy
    private ResourceMapper resourceMapper;
    @Resource @Lazy
    private AccountMapper accountMapper;
    @Resource @Lazy
    private ResourceRuleMapper resourceRuleMapper;
    @Resource @Lazy
    private NoticeService noticeService;
    @Resource @Lazy
    private ProxyMapper proxyMapper;
    @Resource @Lazy
    private OrderService orderService;
    @Resource @Lazy
    private RuleMapper ruleMapper;
    @Resource @Lazy
    private ResourceItemMapper resourceItemMapper;
    @Resource @Lazy
    private ExtCloudTaskMapper extCloudTaskMapper;

    public CloudTask createTask(QuartzTaskDTO quartzTaskDTO, String status, String messageOrderId) throws Exception {
        CloudTask cloudTask = createTaskOrder(quartzTaskDTO, status, messageOrderId);
        String taskId = cloudTask.getId();

        String script = quartzTaskDTO.getScript();
        JSONArray jsonArray = JSON.parseArray(quartzTaskDTO.getParameter());
        for (Object o : jsonArray) {
            JSONObject jsonObject = (JSONObject) o;
            String key = "${{" + jsonObject.getString("key") + "}}";
            if (script.contains(key)) {
                script = script.replace(key, jsonObject.getString("defaultValue"));
            }
        }

        this.deleteTaskItems(cloudTask.getId());
        List<String> resourceTypes = new ArrayList();
        for (SelectTag selectTag : quartzTaskDTO.getSelectTags()) {
            for (String regionId : selectTag.getRegions()) {
                CloudTaskItemWithBLOBs taskItemWithBLOBs = new CloudTaskItemWithBLOBs();
                String uuid = UUIDUtil.newUUID();
                taskItemWithBLOBs.setId(uuid);
                taskItemWithBLOBs.setTaskId(cloudTask.getId());
                taskItemWithBLOBs.setRuleId(quartzTaskDTO.getId());
                taskItemWithBLOBs.setCustomData(script);
                taskItemWithBLOBs.setStatus(CloudTaskConstants.TASK_STATUS.UNCHECKED.name());
                taskItemWithBLOBs.setSeverity(quartzTaskDTO.getSeverity());
                taskItemWithBLOBs.setCreateTime(cloudTask.getCreateTime());
                taskItemWithBLOBs.setAccountId(selectTag.getAccountId());
                AccountWithBLOBs account = accountMapper.selectByPrimaryKey(selectTag.getAccountId());
                taskItemWithBLOBs.setAccountUrl(account.getPluginIcon());
                taskItemWithBLOBs.setAccountLabel(account.getName());
                taskItemWithBLOBs.setRegionId(regionId);
                taskItemWithBLOBs.setRegionName(PlatformUtils.tranforRegionId2RegionName(regionId, cloudTask.getPluginId()));
                taskItemWithBLOBs.setTags(cloudTask.getRuleTags());
                cloudTaskItemMapper.insertSelective(taskItemWithBLOBs);

                final String finalScript = script;
                commonThreadPool.addTask(() -> {
                    String sc = "";
                    String dirPath = "";
                    try {
                        LogUtil.info(" ::: Generate nuclei.yml file start ::: ");
                        dirPath = CommandUtils.saveAsFile(finalScript, CloudTaskConstants.RESULT_FILE_PATH_PREFIX + taskId + "/" + regionId, "nuclei.yml");
                        LogUtil.info(" ::: Generate nuclei.yml file end ::: " + dirPath);
                    } catch (Exception e) {
                        LogUtil.error("[{}] Generate nuclei.yml file，and nuclei run failed:{}", taskId + "/" + regionId, e.getMessage());
                    }
                    Yaml yaml = new Yaml();
                    Map map = null;
                    try {
                        map = (Map) yaml.load(new FileInputStream(dirPath + "/nuclei.yml"));
                    } catch (FileNotFoundException e) {
                        LogUtil.error(e.getMessage());
                    }
                    if (map != null) {
                        String dirName = "nuclei";
                        String resourceType = "nuclei";

                        CloudTaskItemResourceWithBLOBs taskItemResource = new CloudTaskItemResourceWithBLOBs();
                        taskItemResource.setTaskId(taskId);
                        taskItemResource.setTaskItemId(taskItemWithBLOBs.getId());
                        taskItemResource.setDirName(dirName);
                        taskItemResource.setResourceType(resourceType);
                        taskItemResource.setResourceName(dirName);

                        //包含actions
                        taskItemResource.setResourceCommandAction(yaml.dump(map));

                        //不包含actions
                        taskItemResource.setResourceCommand(yaml.dump(map));
                        cloudTaskItemResourceMapper.insertSelective(taskItemResource);

                        resourceTypes.add(resourceType);

                        sc = yaml.dump(map);
                        taskItemWithBLOBs.setDetails(sc);
                        cloudTaskItemMapper.updateByPrimaryKeySelective(taskItemWithBLOBs);

                        cloudTask.setResourceTypes(resourceTypes.stream().collect(Collectors.toSet()).toString());
                        cloudTaskMapper.updateByPrimaryKeySelective(cloudTask);
                    }
                });
            }
        }
        //向首页活动添加操作信息
        OperationLogService.log(SessionUtils.getUser(), taskId, cloudTask.getTaskName(), ResourceTypeConstants.TASK.name(), ResourceOperation.CREATE, "创建检测任务");
        return cloudTask;
    }

    private CloudTask createTaskOrder(QuartzTaskDTO quartzTaskDTO, String status, String messageOrderId) throws Exception {
        CloudTask cloudTask = new CloudTask();
        cloudTask.setTaskName(quartzTaskDTO.getTaskName() != null ?quartzTaskDTO.getTaskName():quartzTaskDTO.getName());
        cloudTask.setRuleId(quartzTaskDTO.getId());
        cloudTask.setSeverity(quartzTaskDTO.getSeverity());
        cloudTask.setType(quartzTaskDTO.getType());
        cloudTask.setPluginId(quartzTaskDTO.getPluginId());
        cloudTask.setPluginIcon(quartzTaskDTO.getPluginIcon());
        cloudTask.setPluginName(quartzTaskDTO.getPluginName());
        cloudTask.setRuleTags(quartzTaskDTO.getTags().toString());
        cloudTask.setDescription(quartzTaskDTO.getDescription());
        cloudTask.setAccountId(quartzTaskDTO.getAccountId());
        cloudTask.setApplyUser(Objects.requireNonNull(SessionUtils.getUser()).getId());
        cloudTask.setStatus(status);
        cloudTask.setScanType(ScanTypeConstants.nuclei.name());
        if (quartzTaskDTO.getCron() != null){
            cloudTask.setCron(quartzTaskDTO.getCron());
            cloudTask.setCronDesc(DescCornUtils.descCorn(quartzTaskDTO.getCron()));
        }

        CloudTaskExample example = new CloudTaskExample();
        CloudTaskExample.Criteria criteria = example.createCriteria();
        criteria.andAccountIdEqualTo(quartzTaskDTO.getAccountId()).andTaskNameEqualTo(quartzTaskDTO.getTaskName());
        List<CloudTask> queryCloudTasks = cloudTaskMapper.selectByExample(example);
        if (!queryCloudTasks.isEmpty()) {
            cloudTask.setId(queryCloudTasks.get(0).getId());
            cloudTask.setCreateTime(System.currentTimeMillis());
            cloudTaskMapper.updateByPrimaryKeySelective(cloudTask);
        } else {
            String taskId = IDGenerator.newBusinessId(CloudTaskConstants.TASK_ID_PREFIX, SessionUtils.getUser().getId());
            cloudTask.setId(taskId);
            cloudTask.setCreateTime(System.currentTimeMillis());
            cloudTaskMapper.insertSelective(cloudTask);
        }

        if (StringUtils.isNotEmpty(messageOrderId)) {
            noticeService.createMessageOrderItem(messageOrderId, cloudTask);
        }

        return cloudTask;
    }

    private void deleteTaskItems (String taskId) {
        CloudTaskItemExample cloudTaskItemExample = new CloudTaskItemExample();
        cloudTaskItemExample.createCriteria().andTaskIdEqualTo(taskId);
        List<CloudTaskItem> cloudTaskItems = cloudTaskItemMapper.selectByExample(cloudTaskItemExample);

        for (CloudTaskItem cloudTaskItem : cloudTaskItems) {
            CloudTaskItemLogExample cloudTaskItemLogExample = new CloudTaskItemLogExample();
            cloudTaskItemLogExample.createCriteria().andTaskItemIdEqualTo(cloudTaskItem.getId());
            cloudTaskItemLogMapper.deleteByExample(cloudTaskItemLogExample);

            CloudTaskItemResourceExample cloudTaskItemResourceExample = new CloudTaskItemResourceExample();
            cloudTaskItemResourceExample.createCriteria().andTaskItemIdEqualTo(cloudTaskItem.getId());
            List<CloudTaskItemResource> cloudTaskItemResources = cloudTaskItemResourceMapper.selectByExample(cloudTaskItemResourceExample);
            for (CloudTaskItemResource cloudTaskItemResource : cloudTaskItemResources) {
                resourceMapper.deleteByPrimaryKey(cloudTaskItemResource.getResourceId());
                resourceRuleMapper.deleteByPrimaryKey(cloudTaskItemResource.getResourceId());
            }
            cloudTaskItemResourceMapper.deleteByExample(cloudTaskItemResourceExample);
        }
        cloudTaskItemMapper.deleteByExample(cloudTaskItemExample);
    }

    public void createNucleiResource(CloudTaskItemWithBLOBs taskItem, CloudTask cloudTask) throws Exception {
        LogUtil.info("createResource for taskItem: {}", toJSONString(taskItem));
        String operation = Translator.get("i18n_create_resource");
        String resultStr = "", fileName = "nuclei.yml";
        try {
            CloudTaskItemResourceExample example = new CloudTaskItemResourceExample();
            example.createCriteria().andTaskIdEqualTo(cloudTask.getId()).andTaskItemIdEqualTo(taskItem.getId());
            List<CloudTaskItemResourceWithBLOBs> list = cloudTaskItemResourceMapper.selectByExampleWithBLOBs(example);
            if (list.isEmpty()) return;

            String dirPath = CloudTaskConstants.RESULT_FILE_PATH_PREFIX + cloudTask.getId() + "/" + taskItem.getRegionId();
            AccountWithBLOBs accountWithBLOBs = accountMapper.selectByPrimaryKey(taskItem.getAccountId());
            Map<String, String> map = PlatformUtils.getAccount(accountWithBLOBs, taskItem.getRegionId(), proxyMapper.selectByPrimaryKey(accountWithBLOBs.getProxyId()));
            String command = PlatformUtils.fixedCommand(CommandEnum.nuclei.getCommand(), CommandEnum.run.getCommand(), dirPath, fileName, map);
            if(taskItem.getDetails().contains("workflows:")) {
                command = command.replace("-t", "-w");
            }
            LogUtil.info(cloudTask.getId() + " {}[command]: " + command);
            CommandUtils.saveAsFile(taskItem.getDetails(), dirPath, fileName);//重启服务后容器内文件在/tmp目录下会丢失
            resultStr = CommandUtils.commonExecCmdWithResultByNuclei(command, dirPath);
            if (LogUtil.getLogger().isDebugEnabled()) {
                LogUtil.getLogger().debug("resource created: {}", resultStr);
            }
            if (resultStr.contains("ERR"))
                throw new Exception(Translator.get("i18n_create_resource_failed") + ": " + resultStr);


            for (CloudTaskItemResourceWithBLOBs taskItemResource : list) {

                String resourceType = taskItemResource.getResourceType();
                String resourceName = taskItemResource.getResourceName();
                String taskItemId = taskItem.getId();
                if (StringUtils.equals(cloudTask.getType(), CloudTaskConstants.TaskType.manual.name()))
                    orderService.saveTaskItemLog(taskItemId, "resourceType", Translator.get("i18n_operation_begin") + ": " + operation, StringUtils.EMPTY, true);
                Rule rule = ruleMapper.selectByPrimaryKey(taskItem.getRuleId());
                if (rule == null) {
                    orderService.saveTaskItemLog(taskItemId, taskItemId, Translator.get("i18n_operation_ex") + ": " + operation, Translator.get("i18n_ex_rule_not_exist"), false);
                    throw new Exception(Translator.get("i18n_ex_rule_not_exist") + ":" + taskItem.getRuleId());
                }
                String nucleiRun = resultStr;
                String metadata = resultStr;
                String resources = ReadFileUtils.readToBuffer(dirPath + "/" + CloudTaskConstants.NUCLEI_RUN_RESULT_FILE);

                ResourceWithBLOBs resourceWithBLOBs = new ResourceWithBLOBs();
                if (taskItemResource.getResourceId() != null) {
                    resourceWithBLOBs = resourceMapper.selectByPrimaryKey(taskItemResource.getResourceId());
                }
                resourceWithBLOBs.setCustodianRunLog(nucleiRun);
                resourceWithBLOBs.setMetadata(metadata);
                resourceWithBLOBs.setResources(resources);
                resourceWithBLOBs.setResourceName(resourceName);
                resourceWithBLOBs.setDirName(taskItemResource.getDirName());
                resourceWithBLOBs.setResourceType(resourceType);
                resourceWithBLOBs.setAccountId(taskItem.getAccountId());
                resourceWithBLOBs.setSeverity(taskItem.getSeverity());
                resourceWithBLOBs.setRegionId(taskItem.getRegionId());
                resourceWithBLOBs.setRegionName(taskItem.getRegionName());
                resourceWithBLOBs.setResourceCommand(taskItemResource.getResourceCommand());
                resourceWithBLOBs.setResourceCommandAction(taskItemResource.getResourceCommandAction());
                ResourceWithBLOBs resource = saveResource(resourceWithBLOBs, taskItem, cloudTask, taskItemResource);
                LogUtil.info("The returned data is{}: " + new Gson().toJson(resource));
                NucleiCredential nucleiCredential = new Gson().fromJson(accountWithBLOBs.getCredential(), NucleiCredential.class);
                orderService.saveTaskItemLog(taskItemId, resourceType, Translator.get("i18n_operation_end") + ": " + operation, Translator.get("i18n_vuln") + ": " + resource.getPluginName() + "，"
                        + Translator.get("i18n_domain") + ": " + nucleiCredential.getTargetAddress() + "，" + Translator.get("i18n_rule_type") + ": " + resourceType + "，" + Translator.get("i18n_resource_manage") + ": " + resource.getReturnSum() + "/" + resource.getResourcesSum(), true);
                //执行完删除返回目录文件，以便于下一次操作覆盖
                String deleteResourceDir = "rm -rf " + dirPath;
                CommandUtils.commonExecCmdWithResult(deleteResourceDir, dirPath);
            }

        } catch (Exception e) {
            orderService.saveTaskItemLog(taskItem.getId(), taskItem.getId(), Translator.get("i18n_operation_ex") + ": " + operation, e.getMessage(), false);
            LogUtil.error("createResource, taskItemId: " + taskItem.getId() + ", resultStr:" + resultStr, ExceptionUtils.getStackTrace(e));
            throw e;
        }
    }

    public ResourceWithBLOBs saveResource(ResourceWithBLOBs resourceWithBLOBs, CloudTaskItemWithBLOBs taskItem, CloudTask cloudTask, CloudTaskItemResourceWithBLOBs taskItemResource) {
        try {
            //保存创建的资源
            long now = System.currentTimeMillis();
            resourceWithBLOBs.setCreateTime(now);
            resourceWithBLOBs.setUpdateTime(now);
            resourceWithBLOBs.setResourcesSum((long) 1);
            if (StringUtils.isNotEmpty(resourceWithBLOBs.getResources())) {
                resourceWithBLOBs.setReturnSum((long) 1);
            } else {
                resourceWithBLOBs.setReturnSum((long) 0);
            }

            AccountWithBLOBs account = accountMapper.selectByPrimaryKey(resourceWithBLOBs.getAccountId());
            resourceWithBLOBs = updateResourceSum(resourceWithBLOBs, account);
            NucleiCredential nucleiCredential = new Gson().fromJson(account.getCredential(), NucleiCredential.class);
            SimpleDateFormat sdf = new SimpleDateFormat();// 格式化时间
            sdf.applyPattern("yyyy-MM-dd HH:mm:ss a");// a为am/pm的标记
            Date date = new Date();// 获取当前时间
            String json = "{\n" +
                    "  \"id\": " + "\"" + UUIDUtil.newUUID() + "\"" + ",\n" +
                    "  \"CreatedTime\": " + "\"" + sdf.format(date) + "\"" + ",\n" +
                    "  \"Domain\": " + "\"" + nucleiCredential.getTargetAddress() + "\"" + ",\n" +
                    "  \"Result\": " + "\"" + resourceWithBLOBs.getResources() + "\"" + "\n" +
                    "}";
            //资源详情
            saveResourceItem(resourceWithBLOBs, parseObject(json));


            //资源、规则、申请人关联表
            ResourceRule resourceRule = new ResourceRule();
            resourceRule.setResourceId(resourceWithBLOBs.getId());
            resourceRule.setRuleId(taskItem.getRuleId());
            resourceRule.setApplyUser(cloudTask.getApplyUser());
            if (resourceRuleMapper.selectByPrimaryKey(resourceWithBLOBs.getId()) != null) {
                resourceRuleMapper.updateByPrimaryKeySelective(resourceRule);
            } else {
                resourceRuleMapper.insertSelective(resourceRule);
            }

            //任务条目和资源关联表
            taskItemResource.setResourceId(resourceWithBLOBs.getId());
            insertTaskItemResource(taskItemResource);

            //计算sum资源总数与检测的资源数到task
            int resourceSum = extCloudTaskMapper.getResourceSum(cloudTask.getId());
            int returnSum = extCloudTaskMapper.getReturnSum(cloudTask.getId());
            cloudTask.setResourcesSum((long) resourceSum);
            cloudTask.setReturnSum((long) returnSum);
            cloudTaskMapper.updateByPrimaryKeySelective(cloudTask);
        } catch (Exception e) {
            LogUtil.error(e.getMessage());
            HRException.throwException(e.getMessage());
        }

        return resourceWithBLOBs;
    }

    private ResourceWithBLOBs updateResourceSum(ResourceWithBLOBs resourceWithBLOBs, AccountWithBLOBs account) {
        try {
            resourceWithBLOBs.setPluginIcon(account.getPluginIcon());
            resourceWithBLOBs.setPluginName(account.getPluginName());
            resourceWithBLOBs.setPluginId(account.getPluginId());
            if (resourceWithBLOBs.getReturnSum() > 0) {
                resourceWithBLOBs.setResourceStatus(ResourceConstants.RESOURCE_STATUS.NotFixed.name());
            } else {
                resourceWithBLOBs.setResourceStatus(ResourceConstants.RESOURCE_STATUS.NotNeedFix.name());
            }

            if (resourceWithBLOBs.getId() != null) {
                resourceMapper.updateByPrimaryKeySelective(resourceWithBLOBs);
            } else {
                resourceWithBLOBs.setId(UUIDUtil.newUUID());
                resourceMapper.insertSelective(resourceWithBLOBs);
            }
        } catch (Exception e) {
            LogUtil.error("[{}] Generate updateResourceSum nuclei.yml file，and nuclei run failed:{}", resourceWithBLOBs.getId(), e.getMessage());
            throw e;
        }
        return resourceWithBLOBs;
    }

    private void saveResourceItem(ResourceWithBLOBs resourceWithBLOBs, JSONObject jsonObject) {
        ResourceItem resourceItem = new ResourceItem();
        try{
            String fid = UUIDUtil.newUUID();
            resourceItem.setAccountId(resourceWithBLOBs.getAccountId());
            resourceItem.setUpdateTime(System.currentTimeMillis());
            resourceItem.setPluginIcon(resourceWithBLOBs.getPluginIcon());
            resourceItem.setPluginId(resourceWithBLOBs.getPluginId());
            resourceItem.setPluginName(resourceWithBLOBs.getPluginName());
            resourceItem.setRegionId(resourceWithBLOBs.getRegionId());
            resourceItem.setRegionName(resourceWithBLOBs.getRegionName());
            resourceItem.setResourceId(resourceWithBLOBs.getId());
            resourceItem.setSeverity(resourceWithBLOBs.getSeverity());
            resourceItem.setResourceType(resourceWithBLOBs.getResourceType());
            resourceItem.setHummerId(fid);
            resourceItem.setResource(jsonObject.toJSONString());

            ResourceItemExample example = new ResourceItemExample();
            example.createCriteria().andResourceIdEqualTo(resourceWithBLOBs.getId());
            List<ResourceItem> resourceItems = resourceItemMapper.selectByExampleWithBLOBs(example);
            if (!resourceItems.isEmpty()) {
                resourceItem.setId(resourceItems.get(0).getId());
                resourceItemMapper.updateByPrimaryKeySelective(resourceItem);
            } else {
                resourceItem.setId(UUIDUtil.newUUID());
                resourceItem.setCreateTime(System.currentTimeMillis());
                resourceItemMapper.insertSelective(resourceItem);
            }
        } catch (Exception e) {
            LogUtil.error(e.getMessage());
            throw e;
        }
    }

    private void insertTaskItemResource(CloudTaskItemResourceWithBLOBs taskItemResource) {
        if (taskItemResource.getId() != null) {
            cloudTaskItemResourceMapper.updateByPrimaryKeySelective(taskItemResource);
        } else {
            cloudTaskItemResourceMapper.insertSelective(taskItemResource);
        }
    }
}
