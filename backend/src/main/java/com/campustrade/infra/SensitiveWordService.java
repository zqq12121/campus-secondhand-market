package com.campustrade.infra;

import com.campustrade.module.admin.content.entity.SensitiveWord;
import com.campustrade.module.admin.content.mapper.SensitiveWordMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** DFA 敏感词过滤：启动加载，词库变更后调用 reload() */
@Service
@RequiredArgsConstructor
public class SensitiveWordService {

    private final SensitiveWordMapper mapper;
    private volatile Node root = new Node();

    private static final class Node {
        final Map<Character, Node> next = new HashMap<>();
        boolean end;
    }

    @PostConstruct
    public void reload() {
        Node r = new Node();
        for (SensitiveWord w : mapper.selectList(null)) {
            Node n = r;
            for (char c : w.getWord().toLowerCase().toCharArray()) {
                n = n.next.computeIfAbsent(c, k -> new Node());
            }
            n.end = true;
        }
        root = r;
    }

    /** 返回命中的敏感词（去重，保持出现顺序） */
    public List<String> findHits(String text) {
        Set<String> hits = new LinkedHashSet<>();
        scan(text, (start, end) -> hits.add(text.substring(start, end)));
        return List.copyOf(hits);
    }

    /** 命中的词替换为 *** */
    public String replace(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        int[] cursor = {0};
        scan(text, (start, end) -> {
            sb.append(text, cursor[0], start).append("***");
            cursor[0] = end;
        });
        return sb.append(text.substring(cursor[0])).toString();
    }

    private interface HitConsumer {
        void accept(int start, int end);
    }

    /** 最长匹配，命中后从词尾继续 */
    private void scan(String text, HitConsumer consumer) {
        if (text == null) {
            return;
        }
        String lower = text.toLowerCase();
        Node r = root;
        int i = 0;
        while (i < lower.length()) {
            Node n = r;
            int matchEnd = -1;
            for (int j = i; j < lower.length(); j++) {
                n = n.next.get(lower.charAt(j));
                if (n == null) {
                    break;
                }
                if (n.end) {
                    matchEnd = j + 1;
                }
            }
            if (matchEnd > 0) {
                consumer.accept(i, matchEnd);
                i = matchEnd;
            } else {
                i++;
            }
        }
    }
}
