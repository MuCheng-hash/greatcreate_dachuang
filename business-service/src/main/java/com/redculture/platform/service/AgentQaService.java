package com.redculture.platform.service;

import com.redculture.platform.vo.AgentQaResponse;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.AssistantConversationTurnCancellation;
import com.redculture.platform.vo.ai.AgentActionVO;
import com.redculture.platform.vo.request.AgentQaRequest;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface AgentQaService {

    /**
     * 在已认证用户的可见数据范围内完成一次非流式 Agent 问答。
     * 调用方必须传入 HTTP 认证层解析出的 currentUser，服务据此隔离学校、班级和学生任务；
     * 请求不具备访问资格或上游 Agent 不可用时以响应式错误信号返回，而不产生跨用户结果。
     */
    Mono<AgentQaResponse> ask(AgentQaRequest request, AuthCurrentUserVO currentUser);

    /**
     * 以 SSE 增量返回 Agent 的思考、工具和最终答案事件。
     * clientTurnId 是同一会话轮次的恢复与幂等标识；订阅中断后可由控制器使用它继续本轮，
     * 但每个事件仍仅能暴露 currentUser 有权访问的上下文和操作。
     */
    Flux<ServerSentEvent<Map<String, Object>>> stream(
            AgentQaRequest request, AuthCurrentUserVO currentUser);

    /**
     * 请求终止当前用户拥有的未完成会话轮次，并返回实际取消状态。
     * 不允许通过 clientTurnId 取消其他账号创建的执行，重复取消保持可安全处理。
     */
    Mono<AssistantConversationTurnCancellation> cancelTurn(
            String clientTurnId, AuthCurrentUserVO currentUser);

    /** 查询当前用户可见的待确认 Agent 操作及其执行状态。 */
    Mono<AgentActionVO> getAction(String actionId, AuthCurrentUserVO currentUser);

    /**
     * 记录当前用户对待确认操作的同意或拒绝，并在通过时触发受控执行。
     * 服务会校验操作归属与状态，避免重复决策造成重复副作用。
     */
    Mono<AgentActionVO> decideAction(
            String actionId, String decision, AuthCurrentUserVO currentUser);
}
