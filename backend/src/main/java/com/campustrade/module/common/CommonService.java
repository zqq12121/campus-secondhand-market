package com.campustrade.module.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.entity.Campus;
import com.campustrade.entity.Category;
import com.campustrade.mapper.CampusMapper;
import com.campustrade.mapper.CategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 校区、分类等基础数据 */
@Service
@RequiredArgsConstructor
public class CommonService {

    private final CampusMapper campusMapper;
    private final CategoryMapper categoryMapper;

    public record CampusVO(Long id, String name) {
    }

    public record CategoryNode(Long id, String name, String iconUrl, Integer sort, boolean enabled,
                               List<CategoryNode> children) {
    }

    public List<CampusVO> campuses() {
        return campusMapper.selectList(new LambdaQueryWrapper<Campus>().orderByAsc(Campus::getSort))
                .stream().map(c -> new CampusVO(c.getId(), c.getName())).toList();
    }

    public Map<Long, String> campusNames() {
        return campusMapper.selectList(null).stream().collect(Collectors.toMap(Campus::getId, Campus::getName));
    }

    /** 分类树；onlyEnabled=true 时过滤停用分类（停用的一级分类连同子分类一起隐藏） */
    public List<CategoryNode> categoryTree(boolean onlyEnabled) {
        List<Category> all = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .eq(onlyEnabled, Category::getEnabled, 1).orderByAsc(Category::getSort, Category::getId));
        Map<Long, List<Category>> byParent = all.stream().collect(Collectors.groupingBy(Category::getParentId));
        List<CategoryNode> roots = new ArrayList<>();
        for (Category root : byParent.getOrDefault(0L, List.of())) {
            List<CategoryNode> children = byParent.getOrDefault(root.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(Category::getSort))
                    .map(c -> new CategoryNode(c.getId(), c.getName(), c.getIconUrl(), c.getSort(),
                            c.getEnabled() == 1, List.of()))
                    .toList();
            roots.add(new CategoryNode(root.getId(), root.getName(), root.getIconUrl(), root.getSort(),
                    root.getEnabled() == 1, children));
        }
        return roots;
    }

    /** 一级分类返回其全部子分类 ID，二级分类返回自身 */
    public List<Long> expandCategory(long categoryId) {
        Category c = categoryMapper.selectById(categoryId);
        if (c == null) {
            return List.of(-1L);
        }
        if (c.getParentId() != 0) {
            return List.of(c.getId());
        }
        List<Long> ids = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .eq(Category::getParentId, categoryId)).stream().map(Category::getId).toList();
        return ids.isEmpty() ? List.of(-1L) : ids;
    }

    /** 启用中的二级分类（且父分类启用），否则返回 null */
    public Category enabledLeaf(Long categoryId) {
        Category c = categoryId == null ? null : categoryMapper.selectById(categoryId);
        if (c == null || c.getParentId() == 0 || c.getEnabled() != 1) {
            return null;
        }
        Category parent = categoryMapper.selectById(c.getParentId());
        return parent != null && parent.getEnabled() == 1 ? c : null;
    }

    /** ["一级", "二级"] */
    public List<String> categoryPath(Long categoryId) {
        Category c = categoryId == null ? null : categoryMapper.selectById(categoryId);
        if (c == null) {
            return List.of();
        }
        if (c.getParentId() == 0) {
            return List.of(c.getName());
        }
        Category p = categoryMapper.selectById(c.getParentId());
        return p == null ? List.of(c.getName()) : List.of(p.getName(), c.getName());
    }
}
