package com.linyi.pig.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.linyi.pig.config.OllamaConfig;
import com.linyi.pig.config.VectorStoreCache;
import com.linyi.pig.entity.Conversation;
import com.linyi.pig.entity.ConversationSession;
import com.linyi.pig.entity.vo.conversation.ConversationAddVo;
import com.linyi.pig.entity.vo.conversation.ConversationQueryVo;
import com.linyi.pig.entity.vo.conversation.ConversationUpdateVo;
import com.linyi.pig.exception.LinyiException;
import com.linyi.pig.mapper.ConversationMapper;
import com.linyi.pig.service.ConversationService;
import com.linyi.pig.service.ConversationSessionService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import reactor.core.publisher.Flux;
import com.linyi.pig.common.model.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;

/**
 * @Author: linyi
 * @Date: 2025-02-26 13:27:06
 * @ClassName: ConversationServiceImpl
 * @Version: 1.0
 * @Description: 对话 服务实现层
 */
@Slf4j
@Service
@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, Conversation>
                implements ConversationService {

        @Autowired
        private ConversationMapper conversationMapper;

        @Resource
        private OllamaConfig ollamaConfig;

        @Value("${ai.ollama.chat.options.model}")
        private String defaultChatOptionsModel;

        @Autowired
        private ChatClient chatClient;

        @Autowired
        private VectorStore vectorStore;

        @Autowired
        private VectorStoreCache vectorStoreCache;

        @Autowired
        private ConversationSessionService conversationSessionService;

        @Override
        public PageResult<Conversation> conversationPage(ConversationQueryVo conversationQueryVo) {
                LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
                queryWrapper.eq(Optional.ofNullable(conversationQueryVo.getId()).isPresent(), Conversation::getId,
                                conversationQueryVo.getId());
                queryWrapper.eq(Optional.ofNullable(conversationQueryVo.getUserId()).isPresent(),
                                Conversation::getUserId,
                                conversationQueryVo.getUserId());
                queryWrapper.eq(StringUtils.isNotBlank(conversationQueryVo.getUserInput()), Conversation::getUserInput,
                                conversationQueryVo.getUserInput());
                queryWrapper.eq(StringUtils.isNotBlank(conversationQueryVo.getAiResponse()),
                                Conversation::getAiResponse,
                                conversationQueryVo.getAiResponse());
                queryWrapper.eq(Optional.ofNullable(conversationQueryVo.getAiResponse()).isPresent(),
                                Conversation::getAiResponse, conversationQueryVo.getAiResponse());
                queryWrapper.gt(Optional.ofNullable(conversationQueryVo.getStartConversationTime()).isPresent(),
                                Conversation::getConversationTime, conversationQueryVo.getStartConversationTime());
                queryWrapper.lt(Optional.ofNullable(conversationQueryVo.getEndConversationTime()).isPresent(),
                                Conversation::getConversationTime, conversationQueryVo.getEndConversationTime());
                queryWrapper.eq(StringUtils.isNotBlank(conversationQueryVo.getModelName()), Conversation::getModelName,
                                conversationQueryVo.getModelName());
                queryWrapper.ge(Optional.ofNullable(conversationQueryVo.getResponseTime()).isPresent(),
                                Conversation::getResponseTime, conversationQueryVo.getResponseTime());

                // 分页数据
                Page<Conversation> page = new Page<>(conversationQueryVo.getPageNum(),
                                conversationQueryVo.getPageSize());
                // 查询数据
                Page<Conversation> pageNew = conversationMapper.selectPage(page, queryWrapper);
                // 返回分页数据
                return new PageResult<>(pageNew.getRecords(), pageNew.getTotal(), pageNew.getPages(),
                                conversationQueryVo.getPageNum(), conversationQueryVo.getPageSize());
        }

        @Override
        public Boolean conversationAdd(ConversationAddVo conversationAddVo) {
                // 创建实体对象
                Conversation conversation = new Conversation();
                // 复制属性
                BeanUtils.copyProperties(conversationAddVo, conversation);
                // 插入数据
                return conversationMapper.insert(conversation) > 0 ? true : false;
        }

        @Override
        public Boolean conversationUpdate(ConversationUpdateVo conversationUpdateVo) {
                // 根据ID查询数据
                Conversation byId = this.getById(conversationUpdateVo.getId());
                // 判断数据是否存在
                if (Optional.ofNullable(byId).isEmpty()) {
                        log.error("数据不存在");
                        return false;
                }
                // 复制属性
                BeanUtils.copyProperties(conversationUpdateVo, byId);
                // 修改数据
                return conversationMapper.updateById(byId) > 0 ? true : false;
        }

        @Override
        public List<Conversation> getHistoryNum(Integer num) {
                // 查询数据
                LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
                // 根据用户ID查询历史记录
                queryWrapper.eq(Conversation::getUserId, StpUtil.getLoginId())
                                .orderByDesc(Conversation::getConversationTime).last("limit " + num);
                return conversationMapper.selectList(queryWrapper);
        }

        @Override
        public Conversation getOllama(String msg) {
                return getOllama(msg, null);
        }

        @Override
        public List<Conversation> listBySessionId(Long sessionId) {
                LambdaQueryWrapper<Conversation> qw = new LambdaQueryWrapper<>();
                qw.eq(Conversation::getUserId, StpUtil.getLoginId())
                                .eq(Conversation::getSessionId, sessionId)
                                .orderByAsc(Conversation::getId);
                return conversationMapper.selectList(qw);
        }

        @Override
        public Conversation getOllama(String msg, Long sessionId) {
                log.info("问题是:{}, sessionId:{}", msg, sessionId);

                if (StringUtils.isBlank(msg)) {
                        log.error("请输入chat内容");
                        throw new LinyiException("请输入chat内容");
                }

                int userId = Integer.valueOf(StpUtil.getLoginId().toString());

                if (sessionId == null) {
                        ConversationSession newSession = conversationSessionService.createSession(null, defaultChatOptionsModel);
                        sessionId = newSession.getId();
                        log.info("自动创建新会话, sessionId:{}", sessionId);
                }

                long startTime = System.nanoTime();

                int topK = 5;

                var allSimilar = vectorStoreCache.similaritySearchWithCache(msg, topK);

                StringBuilder context = new StringBuilder();
                int count = 0;
                for (org.springframework.ai.document.Document d : allSimilar) {
                        if (count >= topK) break;
                        context.append("\n---\n");
                        context.append(d.getText());
                        count++;
                }

                List<Conversation> history = listBySessionId(sessionId);

                StringBuilder historyContext = new StringBuilder();
                int maxHistory = 6;
                int startIndex = Math.max(0, history.size() - maxHistory);
                for (int i = startIndex; i < history.size(); i++) {
                        Conversation h = history.get(i);
                        historyContext.append("用户: ").append(h.getUserInput()).append("\n");
                        historyContext.append("助手: ").append(h.getAiResponse()).append("\n");
                }

                String finalPrompt = buildPrompt(historyContext.toString(), context.toString(), msg);

                long retrievalTime = System.nanoTime() - startTime;

                long generationStart = System.nanoTime();
                String content = chatClient
                                .prompt()
                                .user(finalPrompt)
                                .options(OllamaOptions.builder()
                                                .model(defaultChatOptionsModel)
                                                .temperature(0.4)
                                                .numCtx(4096)
                                                .numGPU(1)
                                                .build())
                                .stream()
                                .content()
                                .collectList()
                                .map(list -> String.join("", list))
                                .block();
                long generationTime = System.nanoTime() - generationStart;

                long endTime = System.nanoTime();
                String formattedTimeTaken = String.format("%.2f", (endTime - startTime) / 1e9);

                log.info("RAG查询完成: retrievalTime={}ms, generationTime={}ms, contextLength={}, responseLength={}",
                                retrievalTime / 1_000_000, generationTime / 1_000_000, context.length(), content.length());

                Conversation conversation = Conversation.builder()
                                .userId(userId)
                                .sessionId(sessionId)
                                .userInput(msg)
                                .aiResponse(content)
                                .modelName(defaultChatOptionsModel)
                                .responseTime(new BigDecimal(formattedTimeTaken))
                                .build();
                conversationMapper.insert(conversation);
                return conversation;
        }

        private String buildPrompt(String historyContext, String knowledgeContext, String question) {
                StringBuilder prompt = new StringBuilder();
                prompt.append("你是一名畜禽AI医生。");

                if (!historyContext.isBlank()) {
                        prompt.append("\n历史对话:\n").append(historyContext);
                }

                if (!knowledgeContext.isBlank()) {
                        prompt.append("先基于以下本地知识回答，如果知识未涵盖再综合自身知识作答，并标注依据来源要点。");
                        prompt.append("\n本地知识: ").append(knowledgeContext);
                } else {
                        prompt.append("请根据你的知识回答。");
                }

                prompt.append("\n问题: ").append(question);
                prompt.append("\n请给出清晰、权威且可执行的回答。");

                return prompt.toString();
        }

        @Override
        public Flux<String> streamOllama(String msg, Long sessionId) {
                log.info("流式问题是:{}, sessionId:{}", msg, sessionId);

                if (StringUtils.isBlank(msg)) {
                        log.error("请输入chat内容");
                        throw new LinyiException("请输入chat内容");
                }

                int userId = Integer.valueOf(StpUtil.getLoginId().toString());

                if (sessionId == null) {
                        ConversationSession newSession = conversationSessionService.createSession(null, defaultChatOptionsModel);
                        sessionId = newSession.getId();
                        log.info("自动创建新会话, sessionId:{}", sessionId);
                }

                final Long finalSessionId = sessionId;

                long startTime = System.nanoTime();

                var allSimilar = vectorStoreCache.similaritySearchWithCache(msg, 5);

                StringBuilder context = new StringBuilder();
                for (Document d : allSimilar) {
                        context.append("\n---\n");
                        context.append(d.getText());
                }

                long retrievalTime = System.nanoTime() - startTime;

                List<Conversation> history = listBySessionId(finalSessionId);

                StringBuilder historyContext = new StringBuilder();
                int maxHistory = 6;
                int startIndex = Math.max(0, history.size() - maxHistory);
                for (int i = startIndex; i < history.size(); i++) {
                        Conversation h = history.get(i);
                        historyContext.append("用户: ").append(h.getUserInput()).append("\n");
                        historyContext.append("助手: ").append(h.getAiResponse()).append("\n");
                }

                String finalPrompt = buildPrompt(historyContext.toString(), context.toString(), msg);

                long generationStart = System.nanoTime();
                StringBuilder contentBuilder = new StringBuilder();

                return chatClient.prompt()
                                .user(finalPrompt)
                                .options(OllamaOptions.builder()
                                                .model(defaultChatOptionsModel)
                                                .temperature(0.4)
                                                .numCtx(4096)
                                                .numGPU(1)
                                                .build())
                                .stream()
                                .content()
                                .doOnNext(chunk -> contentBuilder.append(chunk))
                                .doOnComplete(() -> {
                                        long generationTime = System.nanoTime() - generationStart;
                                        long endTime = System.nanoTime();
                                        String content = contentBuilder.toString();
                                        String formattedTimeTaken = String.format("%.2f", (endTime - startTime) / 1e9);

                                        log.info("RAG流式查询完成: retrievalTime={}ms, generationTime={}ms, contextLength={}, responseLength={}",
                                                        retrievalTime / 1_000_000, generationTime / 1_000_000, context.length(), content.length());

                                        try {
                                                Conversation conversation = Conversation.builder()
                                                                .userId(userId)
                                                                .sessionId(finalSessionId)
                                                                .userInput(msg)
                                                                .aiResponse(content)
                                                                .modelName(defaultChatOptionsModel)
                                                                .responseTime(new BigDecimal(formattedTimeTaken))
                                                                .build();
                                                conversationMapper.insert(conversation);
                                        } catch (Exception e) {
                                                log.error("保存对话记录失败: {}", e.getMessage());
                                        }
                                })
                                .onErrorResume(throwable -> {
                                        log.error("流式生成异常: {}", throwable.getMessage(), throwable);
                                        return Flux.just("\n\n[生成过程中出现错误，但已生成部分内容]");
                                });
        }

        @Override
        public Conversation getApiLLM(String prompt) {
                return null;
        }
}
