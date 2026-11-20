package com.campustrade.module.review;

import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.common.R;
import com.campustrade.module.review.ReviewService.PublicReviewVO;
import com.campustrade.module.review.ReviewService.ReplyReq;
import com.campustrade.module.review.ReviewService.ReviewVO;
import com.campustrade.module.review.ReviewService.SubmitReq;
import com.campustrade.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping("/orders/{id}/reviews")
    public R<ReviewVO> submit(@PathVariable long id, @Valid @RequestBody SubmitReq req) {
        return R.ok(reviewService.submit(CurrentUser.id(), id, req));
    }

    @PostMapping("/reviews/{id}/reply")
    public R<Void> reply(@PathVariable long id, @Valid @RequestBody ReplyReq req) {
        reviewService.reply(CurrentUser.id(), id, req.reply());
        return R.ok();
    }

    @GetMapping("/review-tags")
    public R<Map<String, List<String>>> tags() {
        return R.ok(ReviewService.TAGS);
    }

    @GetMapping("/users/{id}/reviews")
    public R<PageResult<PublicReviewVO>> received(@PathVariable long id,
                                                  @RequestParam(required = false) Integer rating, PageQuery pq) {
        return R.ok(reviewService.receivedBy(id, rating, pq));
    }
}
