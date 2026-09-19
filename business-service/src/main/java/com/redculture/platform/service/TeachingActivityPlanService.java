package com.redculture.platform.service;

import com.redculture.platform.common.PageResult;
import com.baomidou.mybatisplus.extension.service.IService;
import com.redculture.platform.entity.TeachingActivityPlan;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.vo.TeachingActivityPlanAdminVO;
import com.redculture.platform.vo.request.TeachingActivityPlanCreateRequest;
import com.redculture.platform.vo.request.TeachingActivityPlanUpdateRequest;
import com.redculture.platform.vo.AuthCurrentUserVO;
import java.time.LocalDateTime;
import java.io.IOException;

public interface TeachingActivityPlanService extends IService<TeachingActivityPlan> {

    /** 创建后台维护的教学活动方案，并校验所属学校、关联资源和方案编号。 */
    TeachingActivityPlanAdminVO createPlan(TeachingActivityPlanCreateRequest request);

    /** 更新后台方案及其资源关联；调用方负责在控制器层限制为具备管理权限的用户。 */
    TeachingActivityPlanAdminVO updatePlan(Long planId, TeachingActivityPlanUpdateRequest request);

    /** 查询后台编辑所需的完整方案详情，不按当前教师所有权过滤。 */
    TeachingActivityPlanAdminVO getPlanAdminDetail(Long planId);

    PageResult<TeachingActivityPlanAdminVO> pagePlans(Long schoolId,
                                                      Long resourceId,
                                                      String theme,
                                                      String activityType,
                                                      ReviewStatus reviewStatus,
                                                      Long pageNum,
                                                      Long pageSize);

    PageResult<TeachingActivityPlanAdminVO> listBySchoolId(Long schoolId, Long pageNum, Long pageSize);

    /**
     * 分页查询当前账号拥有的方案，时间、年级、主题和资源条件仅在该所有权范围内生效。
     * user 来自认证上下文，不能由客户端请求体替代。
     */
    PageResult<TeachingActivityPlanAdminVO> listMine(AuthCurrentUserVO user,
                                                    String grade,
                                                    String theme,
                                                    Long resourceId,
                                                    LocalDateTime createdFrom,
                                                    LocalDateTime createdTo,
                                                    Long pageNum,
                                                    Long pageSize);

    /** 读取当前账号拥有的单个方案；不存在或归属不匹配时按统一的业务异常处理。 */
    TeachingActivityPlanAdminVO getMine(Long planId, AuthCurrentUserVO user);

    /** 在所有权校验通过后更新教师自己的方案及资源关系。 */
    TeachingActivityPlanAdminVO updateMine(Long planId, TeachingActivityPlanUpdateRequest request, AuthCurrentUserVO user);

    /** 复制当前账号拥有的方案，生成独立编号和独立资源关联，原方案不被修改。 */
    TeachingActivityPlanAdminVO copyMine(Long planId, AuthCurrentUserVO user);

    /** 将当前账号拥有的方案渲染为 DOCX 字节流；读取与导出均受所有权约束。 */
    byte[] exportMine(Long planId, AuthCurrentUserVO user) throws IOException;

    /** 教师拥有的方案导出使用后，将其标记为已采用。 */
    TeachingActivityPlanAdminVO adoptMine(Long planId, AuthCurrentUserVO user);
}
