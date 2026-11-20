package com.campustrade.module.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.common.Biz;
import com.campustrade.common.ErrorCode;
import com.campustrade.entity.Category;
import com.campustrade.entity.Goods;
import com.campustrade.entity.WantPost;
import com.campustrade.mapper.CategoryMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.WantPostMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 后台分类管理（接口文档 13.9），只支持两级 */
@Service
@RequiredArgsConstructor
public class AdminCategoryService {

    private final CategoryMapper categoryMapper;
    private final GoodsMapper goodsMapper;
    private final WantPostMapper wantMapper;

    public record CreateReq(@NotNull Long parentId,
                            @NotBlank @Size(max = 32, message = "名称不超过 32 字") String name,
                            @Size(max = 255) String iconUrl,
                            Integer sort) {
    }

    public record UpdateReq(@NotBlank @Size(max = 32, message = "名称不超过 32 字") String name,
                            @Size(max = 255) String iconUrl,
                            Integer sort) {
    }

    public record EnabledReq(@NotNull Boolean enabled) {
    }

    public record SortItem(@NotNull Long id, @NotNull Integer sort) {
    }

    public record IdResp(Long id) {
    }

    public record AdminCategory(Long id, Long parentId, String name, String iconUrl, Integer sort, boolean enabled,
                                int goodsCount, List<AdminCategory> children) {
    }

    /** 全部分类树（含停用）；goodsCount 为未删除商品数，一级分类为其子分类之和 */
    public List<AdminCategory> tree() {
        List<Category> all = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .orderByAsc(Category::getSort, Category::getId));
        Map<Long, Integer> counts = new HashMap<>();
        goodsMapper.selectMaps(new QueryWrapper<Goods>().select("category_id", "COUNT(*) AS cnt")
                        .groupBy("category_id"))
                .forEach(m -> counts.put(((Number) m.get("category_id")).longValue(), ((Number) m.get("cnt")).intValue()));
        Map<Long, List<Category>> byParent = all.stream().collect(Collectors.groupingBy(Category::getParentId));
        List<AdminCategory> roots = new ArrayList<>();
        for (Category root : byParent.getOrDefault(0L, List.of())) {
            List<AdminCategory> children = byParent.getOrDefault(root.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(Category::getSort).thenComparing(Category::getId))
                    .map(c -> node(c, counts.getOrDefault(c.getId(), 0), List.of())).toList();
            int total = children.stream().mapToInt(AdminCategory::goodsCount).sum();
            roots.add(node(root, total, children));
        }
        return roots;
    }

    public IdResp create(CreateReq req) {
        long parentId = req.parentId();
        if (parentId != 0) {
            Category parent = Biz.notNull(categoryMapper.selectById(parentId), ErrorCode.NOT_FOUND);
            Biz.check(parent.getParentId() == 0, ErrorCode.BAD_REQUEST, "只支持两级分类");
        }
        String name = req.name().trim();
        checkNameUnique(parentId, name, null);
        Category c = new Category();
        c.setParentId(parentId);
        c.setName(name);
        c.setIconUrl(blankToNull(req.iconUrl()));
        c.setSort(req.sort() == null ? 0 : req.sort());
        c.setEnabled(1);
        categoryMapper.insert(c);
        return new IdResp(c.getId());
    }

    public void update(long id, UpdateReq req) {
        Category c = require(id);
        String name = req.name().trim();
        checkNameUnique(c.getParentId(), name, id);
        categoryMapper.update(new LambdaUpdateWrapper<Category>().eq(Category::getId, id)
                .set(Category::getName, name).set(Category::getIconUrl, blankToNull(req.iconUrl()))
                .set(Category::getSort, req.sort() == null ? c.getSort() : req.sort()));
    }

    /** 停用一级分类时子分类一并停用；启用二级分类要求一级分类已启用 */
    @Transactional
    public void setEnabled(long id, boolean enabled) {
        Category c = require(id);
        if (enabled && c.getParentId() != 0) {
            Biz.check(require(c.getParentId()).getEnabled() == 1, ErrorCode.BAD_REQUEST, "请先启用所属一级分类");
        }
        categoryMapper.update(new LambdaUpdateWrapper<Category>().eq(Category::getId, id)
                .set(Category::getEnabled, enabled ? 1 : 0));
        if (!enabled && c.getParentId() == 0) {
            categoryMapper.update(new LambdaUpdateWrapper<Category>().eq(Category::getParentId, id)
                    .set(Category::getEnabled, 0));
        }
    }

    @Transactional
    public void sort(List<SortItem> items) {
        for (SortItem it : items) {
            categoryMapper.update(new LambdaUpdateWrapper<Category>().eq(Category::getId, it.id())
                    .set(Category::getSort, it.sort()));
        }
    }

    /** 有子分类、有未删除商品或求购帖时只能停用（8001） */
    public void delete(long id) {
        require(id);
        boolean inUse = categoryMapper.exists(new LambdaQueryWrapper<Category>().eq(Category::getParentId, id))
                || goodsMapper.exists(new LambdaQueryWrapper<Goods>().eq(Goods::getCategoryId, id))
                || wantMapper.exists(new LambdaQueryWrapper<WantPost>().eq(WantPost::getCategoryId, id));
        Biz.check(!inUse, ErrorCode.CATEGORY_IN_USE);
        categoryMapper.deleteById(id);
    }

    // ---------------------------------------------------------------- 内部

    private Category require(long id) {
        return Biz.notNull(categoryMapper.selectById(id), ErrorCode.NOT_FOUND);
    }

    private void checkNameUnique(long parentId, String name, Long selfId) {
        boolean dup = categoryMapper.selectList(new LambdaQueryWrapper<Category>().eq(Category::getParentId, parentId)
                .eq(Category::getName, name)).stream().anyMatch(x -> !Objects.equals(x.getId(), selfId));
        Biz.check(!dup, ErrorCode.BAD_REQUEST, "同级下已有同名分类");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static AdminCategory node(Category c, int goodsCount, List<AdminCategory> children) {
        return new AdminCategory(c.getId(), c.getParentId(), c.getName(), c.getIconUrl(), c.getSort(),
                c.getEnabled() == 1, goodsCount, children);
    }
}
