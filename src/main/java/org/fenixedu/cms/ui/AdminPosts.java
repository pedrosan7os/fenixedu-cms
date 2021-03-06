/**
 * Copyright © 2014 Instituto Superior Técnico
 *
 * This file is part of FenixEdu CMS.
 *
 * FenixEdu CMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * FenixEdu CMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with FenixEdu CMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.fenixedu.cms.ui;

import static org.fenixedu.cms.domain.PermissionEvaluation.ensureCanDoThis;
import static org.fenixedu.cms.ui.SearchUtils.searchPosts;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

import org.fenixedu.bennu.core.security.Authenticate;
import org.fenixedu.bennu.spring.portal.BennuSpringController;
import org.fenixedu.cms.domain.Category;
import org.fenixedu.cms.domain.PermissionEvaluation;
import org.fenixedu.cms.domain.PermissionsArray.Permission;
import org.fenixedu.cms.domain.Post;
import org.fenixedu.cms.domain.PostFile;
import org.fenixedu.cms.domain.PostMetadata;
import org.fenixedu.cms.domain.Site;
import org.fenixedu.cms.domain.SiteActivity;
import org.fenixedu.commons.i18n.LocalizedString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.view.RedirectView;

import com.google.common.base.Strings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pt.ist.fenixframework.Atomic;
import pt.ist.fenixframework.FenixFramework;

@BennuSpringController(AdminSites.class)
@RequestMapping("/cms/posts")
public class AdminPosts {

    private static final int PER_PAGE = 10;
    private static final String JSON = "application/json;charset=utf-8";
    private static final JsonParser JSON_PARSER = new JsonParser();

    @Autowired
    AdminPostsService service;

    @RequestMapping(value = "{slug}", method = RequestMethod.GET)
    public String posts(Model model, @PathVariable String slug,
                    @RequestParam(required = false, defaultValue = "1") int page,
                    @RequestParam(required = false) String query, @RequestParam(required = false) String category,
                    @RequestParam(required = false, defaultValue = "false") boolean showAll) {

        Site site = Site.fromSlug(slug);
        ensureCanDoThis(site,Permission.SEE_POSTS);
        Collection<Post> posts = site.getPostSet();
        if (!Strings.isNullOrEmpty(category)) {
            Category cat = site.categoryForSlug(category);
            posts = cat.getPostsSet();
            model.addAttribute("category", cat);
        }

        if(!showAll) {
            posts = posts.stream().filter(post -> !post.isStaticPost()).collect(Collectors.toList());
        }

        if (!Strings.isNullOrEmpty(query)) {
            posts = searchPosts(posts, query);
        }

        SearchUtils.Partition<Post> partition = new SearchUtils.Partition<>(posts, Post.CREATION_DATE_COMPARATOR, PER_PAGE, page);

        model.addAttribute("site", site);
        model.addAttribute("query", query);

        model.addAttribute("partition", partition);
        model.addAttribute("posts", partition.getItems());

        return "fenixedu-cms/posts";
    }

    @RequestMapping(value = "{slugSite}/{slugPost}/data", method = RequestMethod.GET, produces = JSON)
    public @ResponseBody String data(@PathVariable String slugSite, @PathVariable String slugPost) {
        Site s = Site.fromSlug(slugSite);
        Post post = s.postForSlug(slugPost);
        ensureCanEditPost(post);
        JsonObject data = new JsonObject();
        data.add("post", service.serializePost(post));
        return data.toString();
    }

    @RequestMapping(value = "{slug}/create", method = RequestMethod.POST)
    public RedirectView createPost(@PathVariable(value = "slug") String slug, @RequestParam LocalizedString name) {
        Site s = Site.fromSlug(slug);
        Post post = createPost(s, name);
        return new RedirectView("/cms/posts/" + s.getSlug() + "/" + post.getSlug() + "/edit", true);
    }

    @RequestMapping(value = "{siteSlug}/{postSlug}/edit", method = RequestMethod.GET)
    public String viewEditPost(Model model, @PathVariable String siteSlug, @PathVariable String postSlug) {
        Site site = Site.fromSlug(siteSlug);
        Post post = site.postForSlug(postSlug);
        ensureCanEditPost(post);
        model.addAttribute("site", site);
        model.addAttribute("post", post);
        return "fenixedu-cms/editPost";
    }


    @RequestMapping(value = "{slugSite}/{slugPost}/edit", method = RequestMethod.POST, consumes = JSON, produces = JSON)
    public @ResponseBody String edit(@PathVariable String slugSite, @PathVariable String slugPost, HttpEntity<String> httpEntity) {
        JsonObject postJson = JSON_PARSER.parse(httpEntity.getBody()).getAsJsonObject();
        Site site = Site.fromSlug(slugSite);
        Post post = site.postForSlug(slugPost);
        ensureCanEditPost(post);
        service.processPostChanges(site, post, postJson);
        return data(site.getSlug(), post.getSlug());
    }

    @RequestMapping(value = "{slugSite}/{slugPost}/delete", method = RequestMethod.POST)
    public RedirectView delete(@PathVariable String slugSite, @PathVariable String slugPost) {

        FenixFramework.atomic(() -> {
            Site s = Site.fromSlug(slugSite);
            Post post = s.postForSlug(slugPost);
            ensureCanEditPost(post);
            ensureCanDoThis(s, Permission.DELETE_POSTS);
            if(post.isVisible()) {
                ensureCanDoThis(s, Permission.DELETE_POSTS_PUBLISHED);
            }
            if(!Authenticate.getUser().equals(post.getCreatedBy())) {
                ensureCanDoThis(s, Permission.DELETE_OTHERS_POSTS);
            }
            SiteActivity.deletedPost(post, Site.fromSlug(slugSite), Authenticate.getUser());
            post.delete();
        });
        return new RedirectView("/cms/posts/" + slugSite + "", true);
    }


    @RequestMapping(value = "{slugSite}/{slugPost}/files", method = RequestMethod.POST, produces = JSON)
    public @ResponseBody String addFile(@PathVariable String slugSite, @PathVariable String slugPost,
                    @RequestParam String name, @RequestParam boolean embedded, @RequestParam MultipartFile file) {
        Site s = Site.fromSlug(slugSite);
        Post p = s.postForSlug(slugPost);
        ensureCanEditPost(p);
        PostFile postFile = service.createFile(p, name, embedded, p.getCanViewGroup(), file);
        return service.serializePostFile(postFile).toString();
    }

    @RequestMapping(value = "{slugSite}/{slugPost}/metadata", method = RequestMethod.GET)
    public String viewEditMetadata(Model model, @PathVariable String slugSite, @PathVariable String slugPost) {
        Site s = Site.fromSlug(slugSite);
        Post post = s.postForSlug(slugPost);
        ensureCanEditPost(post);
        PermissionEvaluation.ensureCanDoThis(s, Permission.SEE_METADATA, Permission.EDIT_METADATA);
        model.addAttribute("site", s);
        model.addAttribute("post", post);
        model.addAttribute("metadata", Optional.ofNullable(post.getMetadata()).map(PostMetadata::json).map(
            JsonElement::toString).orElseGet(()->new JsonObject().toString()));
        return "fenixedu-cms/editMetadata";
    }

    @RequestMapping(value = "{slugSite}/{slugPost}/metadata", method = RequestMethod.POST)
    public RedirectView editMetadata(@PathVariable String slugSite,
                                         @PathVariable String slugPost,
                                         @RequestParam String metadata) {
        Site s = Site.fromSlug(slugSite);
        Post post = s.postForSlug(slugPost);
        FenixFramework.atomic(()-> {
            ensureCanEditPost(post);
            PermissionEvaluation.ensureCanDoThis(s, Permission.SEE_METADATA, Permission.EDIT_METADATA);
            post.setMetadata(PostMetadata.internalize(metadata));
        });
        SiteActivity.editedPost(post, Authenticate.getUser());
        return new RedirectView("/cms/posts/" + s.getSlug() + "/" + post.getSlug() + "/metadata", true);
    }

    public static void ensureCanEditPost(Post post) {
        PermissionEvaluation.ensureCanDoThis(post.getSite(), Permission.EDIT_POSTS);
        if(!Authenticate.getUser().equals(post.getCreatedBy())) {
            PermissionEvaluation.ensureCanDoThis(post.getSite(), Permission.EDIT_OTHERS_POSTS);
        }
        if(post.isVisible()) {
            PermissionEvaluation.ensureCanDoThis(post.getSite(), Permission.EDIT_POSTS_PUBLISHED);
        }
    }

    @Atomic(mode = Atomic.TxMode.WRITE)
    private Post createPost(Site site, LocalizedString name) {
        PermissionEvaluation.ensureCanDoThis(site, Permission.CREATE_POST, Permission.EDIT_POSTS);
        Post post = service.createPost(site, name);
        SiteActivity.createdPost(post, Authenticate.getUser());
        return post;
    }

}
