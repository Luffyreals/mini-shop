package com.minishop.common;

import com.minishop.cart.CartHeaderTestConfig;
import com.minishop.config.SecurityConfig;
import com.minishop.user.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The single 404 mechanism (AC-007-3, 7; BR-007-3): {@code NotFoundPageAdvice} serves the page that a controller
 * names with {@code @NotFoundPage}, for a {@code NotFoundException} and for a malformed path id, and only for
 * controllers that carry the annotation.
 *
 * <p>Three throwaway controllers stand for the three kinds in the project: (1) a storefront one with only a view,
 * (2) an admin one with the optional attributes, (3) one without the annotation. They are nested in this class,
 * which Spring Boot's test filter keeps out of component scanning, so they must be imported explicitly. The real
 * controllers go through the same advice in their own slice tests, which is why those did not need any change.
 */
@WebMvcTest(NotFoundPageAdviceTest.StoreController.class)
@Import({SecurityConfig.class, CartHeaderTestConfig.class, NotFoundPageAdviceTest.StoreController.class,
        NotFoundPageAdviceTest.AdminLikeController.class, NotFoundPageAdviceTest.PlainController.class})
class NotFoundPageAdviceTest {

    @Controller
    @NotFoundPage(view = "product/not-found")
    static class StoreController {

        @GetMapping("/t1/{id}")
        @ResponseBody
        String byId(@PathVariable Long id) {
            return "ok " + id;
        }

        @GetMapping("/t1/missing")
        String missing() {
            throw new NotFoundException("no such thing");
        }
    }

    @Controller
    @NotFoundPage(view = "admin/not-found", title = "Không tìm thấy mục thử", backUrl = "/admin/t-back",
            backLabel = "Quay lại thử", section = "orders")
    static class AdminLikeController {

        @GetMapping("/t2/{id}")
        @ResponseBody
        String byId(@PathVariable Long id) {
            return "ok " + id;
        }

        @GetMapping("/t2/missing")
        String missing() {
            throw new NotFoundException("no such thing");
        }
    }

    @Controller
    static class PlainController {

        @GetMapping("/t3/{id}")
        @ResponseBody
        String byId(@PathVariable Long id) {
            return "ok " + id;
        }

        @GetMapping("/t3/missing")
        String missing() {
            throw new NotFoundException("no such thing");
        }
    }

    @Autowired
    MockMvc mockMvc;

    // ------------------------------------------------- (1) storefront, view only

    @Test
    void notFoundException_inControllerWithAnnotation_rendersItsViewWith404() throws Exception {
        mockMvc.perform(get("/t1/missing").with(user(TestUsers.user())))
                .andExpect(status().isNotFound())
                .andExpect(view().name("product/not-found"))
                .andExpect(content().string(containsString("Không tìm thấy sản phẩm")))
                .andExpect(model().attributeDoesNotExist("notFoundTitle", "backUrl", "backLabel", "section"));
    }

    @Test
    void malformedPathId_inControllerWithAnnotation_rendersItsViewWith404NotBadRequest() throws Exception {
        mockMvc.perform(get("/t1/abc").with(user(TestUsers.user())))
                .andExpect(status().isNotFound())
                .andExpect(view().name("product/not-found"));
        mockMvc.perform(get("/t1/99999999999999999999").with(user(TestUsers.user())))
                .andExpect(status().isNotFound())
                .andExpect(view().name("product/not-found"));
    }

    @Test
    void validPathId_inControllerWithAnnotation_isNotTouchedByTheAdvice() throws Exception {
        mockMvc.perform(get("/t1/5").with(user(TestUsers.user())))
                .andExpect(status().isOk())
                .andExpect(content().string("ok 5"));
    }

    // ------------------------------------------------- (2) admin style, all attributes

    @Test
    void notFoundException_inControllerWithAllAttributes_putsFourModelKeysAndRendersAdminLayout() throws Exception {
        mockMvc.perform(get("/t2/missing").with(user(TestUsers.admin())))
                .andExpect(status().isNotFound())
                .andExpect(view().name("admin/not-found"))
                .andExpect(model().attribute("notFoundTitle", "Không tìm thấy mục thử"))
                .andExpect(model().attribute("backUrl", "/admin/t-back"))
                .andExpect(model().attribute("backLabel", "Quay lại thử"))
                .andExpect(model().attribute("section", "orders"))
                .andExpect(content().string(containsString("<title>Không tìm thấy mục thử | Quản trị Mini Shop</title>")))
                .andExpect(content().string(containsString("<h1 class=\"admin-title\">Không tìm thấy mục thử</h1>")))
                .andExpect(content().string(containsString("href=\"/admin/t-back\">Quay lại thử</a>")))
                .andExpect(content().string(containsString("aria-current=\"page\">Đơn hàng</a>")))
                .andExpect(content().string(containsString("Xin chào, Quản trị viên")));
    }

    @Test
    void malformedPathId_inControllerWithAllAttributes_rendersTheSameAdminPageWith404() throws Exception {
        mockMvc.perform(get("/t2/abc").with(user(TestUsers.admin())))
                .andExpect(status().isNotFound())
                .andExpect(view().name("admin/not-found"))
                .andExpect(model().attribute("section", "orders"))
                .andExpect(content().string(containsString("<title>Không tìm thấy mục thử | Quản trị Mini Shop</title>")));
    }

    // ------------------------------------------------- (3) no annotation

    @Test
    void notFoundException_inControllerWithoutAnnotation_is404ThroughResponseStatusWithNoPageOfItsOwn()
            throws Exception {
        mockMvc.perform(get("/t3/missing").with(user(TestUsers.user())))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getModelAndView()).as("no page of its own").satisfiesAnyOf(
                        mav -> assertThat(mav).isNull(), mav -> assertThat(mav.getViewName()).isNull()))
                .andExpect(content().string(not(containsString("Không tìm thấy sản phẩm"))))
                .andExpect(content().string(not(containsString("Không tìm thấy mục thử"))));
    }

    @Test
    void malformedPathId_inControllerWithoutAnnotation_staysBadRequest() throws Exception {
        mockMvc.perform(get("/t3/abc").with(user(TestUsers.user())))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getModelAndView()).as("no page of its own").satisfiesAnyOf(
                        mav -> assertThat(mav).isNull(), mav -> assertThat(mav.getViewName()).isNull()));
    }

    // ------------------------------------------------- the advice itself

    @Test
    void advice_hasHighestPrecedenceBecauseSpringPicksAdviceByOrderNotBySpecificity() {
        Order order = NotFoundPageAdvice.class.getAnnotation(Order.class);

        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
