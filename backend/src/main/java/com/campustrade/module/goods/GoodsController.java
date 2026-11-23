package com.campustrade.module.goods;

import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.common.R;
import com.campustrade.module.goods.GoodsDtos.DetailVO;
import com.campustrade.module.goods.GoodsDtos.FavoriteCard;
import com.campustrade.module.goods.GoodsDtos.FavoriteResp;
import com.campustrade.module.goods.GoodsDtos.GoodsCard;
import com.campustrade.module.goods.GoodsDtos.MyGoodsPage;
import com.campustrade.module.goods.GoodsDtos.SaveReq;
import com.campustrade.module.goods.GoodsDtos.SaveResp;
import com.campustrade.module.goods.GoodsDtos.SearchQuery;
import com.campustrade.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GoodsController {

    private final GoodsService goodsService;

    @GetMapping("/goods")
    public R<PageResult<GoodsCard>> search(@Valid SearchQuery q) {
        return R.ok(goodsService.search(q, CurrentUser.idOrNull()));
    }

    @GetMapping("/search/hot-words")
    public R<List<String>> hotWords() {
        return R.ok(goodsService.hotWords());
    }

    /** 猜你喜欢：登录后按兴趣推荐，未登录返回热门 */
    @GetMapping("/goods/recommend")
    public R<List<GoodsCard>> recommend(@RequestParam(required = false) Integer size) {
        return R.ok(goodsService.recommend(CurrentUser.idOrNull(), size));
    }

    @GetMapping("/goods/{id}")
    public R<DetailVO> detail(@PathVariable long id, HttpServletRequest http) {
        return R.ok(goodsService.detail(id, CurrentUser.idOrNull(), http.getRemoteAddr()));
    }

    @PostMapping("/goods")
    public R<SaveResp> create(@Valid @RequestBody SaveReq req) {
        return R.ok(goodsService.create(CurrentUser.id(), req));
    }

    @PutMapping("/goods/{id}")
    public R<SaveResp> update(@PathVariable long id, @Valid @RequestBody SaveReq req) {
        return R.ok(goodsService.update(CurrentUser.id(), id, req));
    }

    @PostMapping("/goods/{id}/off-shelf")
    public R<Void> offShelf(@PathVariable long id) {
        goodsService.offShelf(CurrentUser.id(), id);
        return R.ok();
    }

    @PostMapping("/goods/{id}/on-shelf")
    public R<SaveResp> onShelf(@PathVariable long id) {
        return R.ok(goodsService.onShelf(CurrentUser.id(), id));
    }

    @DeleteMapping("/goods/{id}")
    public R<Void> delete(@PathVariable long id) {
        goodsService.delete(CurrentUser.id(), id);
        return R.ok();
    }

    @GetMapping("/users/me/goods")
    public R<MyGoodsPage> mine(@RequestParam(required = false) Integer status, PageQuery pq) {
        return R.ok(goodsService.mine(CurrentUser.id(), status, pq));
    }

    @GetMapping("/users/{id}/goods")
    public R<PageResult<GoodsCard>> onSaleOf(@PathVariable long id, PageQuery pq) {
        return R.ok(goodsService.onSaleOf(id, pq));
    }

    @PostMapping("/goods/{id}/favorite")
    public R<FavoriteResp> favorite(@PathVariable long id) {
        return R.ok(goodsService.favorite(CurrentUser.id(), id));
    }

    @DeleteMapping("/goods/{id}/favorite")
    public R<FavoriteResp> unfavorite(@PathVariable long id) {
        return R.ok(goodsService.unfavorite(CurrentUser.id(), id));
    }

    @GetMapping("/users/me/favorites")
    public R<PageResult<FavoriteCard>> favorites(PageQuery pq) {
        return R.ok(goodsService.favorites(CurrentUser.id(), pq));
    }
}
