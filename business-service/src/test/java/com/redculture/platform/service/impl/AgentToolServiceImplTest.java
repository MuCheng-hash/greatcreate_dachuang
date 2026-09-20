package com.redculture.platform.service.impl;

import com.redculture.platform.entity.ClassLearningTask;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.entity.StudentProfile;
import com.redculture.platform.entity.TaskResourceRel;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.mapper.ClassLearningTaskMapper;
import com.redculture.platform.mapper.ClassMemberMapper;
import com.redculture.platform.mapper.SchoolResourceRelMapper;
import com.redculture.platform.mapper.StudentProfileMapper;
import com.redculture.platform.mapper.StudentTaskProgressMapper;
import com.redculture.platform.mapper.TaskResourceRelMapper;
import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.agent.AgentAccessGuard;
import com.redculture.platform.vo.ai.AgentActorVO;
import com.redculture.platform.vo.ai.AgentScopeVO;
import com.redculture.platform.vo.ai.AgentToolRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolServiceImplTest {

    @Test
    void recomputesStudentTaskResourceIdsInsteadOfAcceptingAgentPayload() {
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        LocalEduResourceService resourceService = mock(LocalEduResourceService.class);
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(any(KnowledgeRetrieveRequest.class))).thenReturn(KnowledgeRetrieveResult.empty());
        AgentToolServiceImpl service = new AgentToolServiceImpl(
                new AgentAccessGuard(schoolMapService), schoolMapService, resourceService, retriever
        );

        StudentProfileMapper studentProfileMapper = mock(StudentProfileMapper.class);
        StudentTaskProgressMapper progressMapper = mock(StudentTaskProgressMapper.class);
        ClassLearningTaskMapper taskMapper = mock(ClassLearningTaskMapper.class);
        ClassMemberMapper memberMapper = mock(ClassMemberMapper.class);
        TaskResourceRelMapper taskResourceMapper = mock(TaskResourceRelMapper.class);
        SchoolResourceRelMapper schoolResourceMapper = mock(SchoolResourceRelMapper.class);
        ReflectionTestUtils.setField(service, "studentProfileMapper", studentProfileMapper);
        ReflectionTestUtils.setField(service, "studentTaskProgressMapper", progressMapper);
        ReflectionTestUtils.setField(service, "classLearningTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "classMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "taskResourceRelMapper", taskResourceMapper);
        ReflectionTestUtils.setField(service, "schoolResourceRelMapper", schoolResourceMapper);

        StudentProfile student = new StudentProfile();
        student.setStudentId(21L);
        student.setStatus("active");
        ClassLearningTask task = new ClassLearningTask();
        task.setTaskId(31L);
        task.setClassId(41L);
        task.setStatus("published");
        TaskResourceRel relation = new TaskResourceRel();
        relation.setResourceId(51L);
        LocalEduResource resource = new LocalEduResource();
        resource.setResourceId(51L);
        resource.setActive(true);
        resource.setReviewStatus(ReviewStatus.APPROVED);
        when(studentProfileMapper.selectOne(any())).thenReturn(student);
        when(taskMapper.selectById(31L)).thenReturn(task);
        when(memberMapper.exists(any())).thenReturn(true);
        when(progressMapper.exists(any())).thenReturn(true);
        when(taskResourceMapper.selectList(any())).thenReturn(List.of(relation));
        when(resourceService.getById(51L)).thenReturn(resource);
        when(schoolResourceMapper.exists(any())).thenReturn(true);

        AgentToolRequest request = new AgentToolRequest();
        AgentActorVO actor = new AgentActorVO();
        actor.setAccountId(1L);
        actor.setRoleCode("student");
        actor.setSchoolId(7L);
        AgentScopeVO scope = new AgentScopeVO();
        scope.setScopeType("SCHOOL");
        scope.setScopeId(7L);
        request.setActor(actor);
        request.setScope(scope);
        request.setTaskId(31L);
        request.setQuery("任务资源讲解");

        service.knowledgeRetrieve(request);

        ArgumentCaptor<KnowledgeRetrieveRequest> captured = ArgumentCaptor.forClass(KnowledgeRetrieveRequest.class);
        verify(retriever).retrieve(captured.capture());
        assertEquals(List.of(51L), captured.getValue().getResourceIds());
    }
}
