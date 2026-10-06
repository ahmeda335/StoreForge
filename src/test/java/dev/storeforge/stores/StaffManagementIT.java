package dev.storeforge.stores;

import static dev.storeforge.support.StoreFlows.bearer;
import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.AuthFlows;
import dev.storeforge.support.AuthFlows.Session;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StaffManagementIT extends IntegrationTest {

    AuthFlows auth;
    StoreFlows stores;
    Owner owner;

    @BeforeEach
    void storeWithOwner() {
        auth = new AuthFlows(api, mailbox);
        stores = new StoreFlows(api, mailbox);
        owner = stores.newOwnerWithStore(uniqueEmail());
    }

    @Test
    void invitedPersonJoinsWithTheInvitedRole() {
        String email = uniqueEmail();
        Session staff = stores.addMember(owner, email, stores.roleId(owner, "Staff"));

        var members = Json.parse(api.get(base() + "/members", owner.auth()).body()).get("data");
        assertThat(members.valueStream().filter(m -> m.get("email").asString().equals(email)).findFirst().orElseThrow()
                .get("roleName").asString()).isEqualTo("Staff");
        assertThat(api.get(base(), bearer(staff.accessToken())).statusCode()).isEqualTo(200);
    }

    @Test
    void invitationsOnlyWorkForTheInvitedEmailAndOnlyOnce() {
        String invitee = uniqueEmail();
        api.post(base() + "/invitations", Json.object("email", invitee, "roleId", stores.roleId(owner, "Staff").toString()), owner.auth());
        String token = mailbox.latestToken(invitee, "invited").orElseThrow();

        Session stranger = auth.newVerifiedSession(uniqueEmail());
        HttpResponse<String> byStranger = stores.acceptInvitation(stranger.accessToken(), owner.storeId(), token);
        assertThat(byStranger.statusCode()).isEqualTo(404);
        assertThat(code(byStranger)).isEqualTo("invitation_not_found");

        Session invited = auth.newVerifiedSession(invitee);
        assertThat(stores.acceptInvitation(invited.accessToken(), owner.storeId(), token).statusCode())
                .as("the stranger's failed attempt must not have consumed the invitation").isEqualTo(204);
        assertThat(stores.acceptInvitation(invited.accessToken(), owner.storeId(), token).statusCode()).isEqualTo(404);
    }

    @Test
    void membersCannotGrantMoreThanTheyHave() {
        UUID managerRole = stores.createRole(owner, "Manager", "staff:read", "staff:manage", "products:read");
        Session manager = stores.addMember(owner, uniqueEmail(), managerRole);
        UUID readOnlyRole = stores.createRole(owner, "Viewer", "products:read");

        assertThat(invite(manager, uniqueEmail(), stores.roleId(owner, "Owner")).statusCode()).isEqualTo(403);
        assertThat(invite(manager, uniqueEmail(), stores.roleId(owner, "Staff")).statusCode())
                .as("Staff has products:write, which the manager lacks").isEqualTo(403);
        assertThat(invite(manager, uniqueEmail(), readOnlyRole).statusCode()).isEqualTo(201);

        UUID ownerUserId = stores.userId(owner.session());
        HttpResponse<String> demoteOwner = api.patch(base() + "/members/" + ownerUserId,
                Json.object("roleId", readOnlyRole.toString()), bearer(manager.accessToken()));
        assertThat(demoteOwner.statusCode()).isEqualTo(403);
        assertThat(code(demoteOwner)).isEqualTo("permission_denied");

        HttpResponse<String> selfPromotion = api.patch(base() + "/members/" + stores.userId(manager),
                Json.object("roleId", stores.roleId(owner, "Owner").toString()), bearer(manager.accessToken()));
        assertThat(selfPromotion.statusCode()).isEqualTo(403);
    }

    @Test
    void permissionsAreEnforcedPerEndpoint() {
        Session staff = stores.addMember(owner, uniqueEmail(), stores.roleId(owner, "Staff"));

        HttpResponse<String> auditLog = api.get(base() + "/audit-log", bearer(staff.accessToken()));
        HttpResponse<String> createRole = api.post(base() + "/roles",
                "{\"name\":\"x\",\"permissions\":[]}", bearer(staff.accessToken()));

        assertThat(auditLog.statusCode()).isEqualTo(403);
        assertThat(code(auditLog)).isEqualTo("permission_denied");
        assertThat(createRole.statusCode()).isEqualTo(403);
    }

    @Test
    void theLastOwnerCannotLeaveOrBeDemoted() {
        UUID ownerUserId = stores.userId(owner.session());

        HttpResponse<String> leave = api.delete(base() + "/members/" + ownerUserId, owner.auth());
        HttpResponse<String> demote = api.patch(base() + "/members/" + ownerUserId,
                Json.object("roleId", stores.roleId(owner, "Staff").toString()), owner.auth());

        assertThat(leave.statusCode()).isEqualTo(409);
        assertThat(code(leave)).isEqualTo("last_owner");
        assertThat(code(demote)).isEqualTo("last_owner");
    }

    /**
     * Write skew: two owners demote each other at the same instant. Without the store row lock both
     * transactions count 2 owners and both succeed, leaving 0 owners. With it, the second waits, then
     * counts 1 owner and is rejected.
     */
    @Test
    void concurrentMutualDemotionNeverLeavesTheStoreWithoutAnOwner() throws Exception {
        UUID ownerRole = stores.roleId(owner, "Owner");
        UUID staffRole = stores.roleId(owner, "Staff");
        Session secondOwner = stores.addMember(owner, uniqueEmail(), ownerRole);
        UUID firstId = stores.userId(owner.session());
        UUID secondId = stores.userId(secondOwner);

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            results.add(executor.submit(() -> {
                start.await();
                return api.patch(base() + "/members/" + secondId, Json.object("roleId", staffRole.toString()), owner.auth()).statusCode();
            }));
            results.add(executor.submit(() -> {
                start.await();
                return api.patch(base() + "/members/" + firstId, Json.object("roleId", staffRole.toString()),
                        bearer(secondOwner.accessToken())).statusCode();
            }));
            start.countDown();
            List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
            assertThat(statuses).containsExactlyInAnyOrder(204, 409);

            // Whoever was NOT demoted is still an owner and can read the member list.
            var survivor = statuses.get(0) == 204 ? owner.auth() : bearer(secondOwner.accessToken());
            long owners = Json.parse(api.get(base() + "/members", survivor).body()).get("data").valueStream()
                    .filter(member -> member.get("owner").asBoolean())
                    .count();
            assertThat(owners).isEqualTo(1);
        }
    }

    @Test
    void rolesInUseAndTheOwnerRoleCannotBeDeleted() {
        UUID staffRole = stores.roleId(owner, "Staff");
        stores.addMember(owner, uniqueEmail(), staffRole);

        HttpResponse<String> inUse = api.delete(base() + "/roles/" + staffRole, owner.auth());
        HttpResponse<String> ownerRole = api.delete(base() + "/roles/" + stores.roleId(owner, "Owner"), owner.auth());
        UUID unused = stores.createRole(owner, "Temporary", "products:read");
        HttpResponse<String> deleted = api.delete(base() + "/roles/" + unused, owner.auth());

        assertThat(code(inUse)).isEqualTo("role_in_use");
        assertThat(code(ownerRole)).isEqualTo("role_protected");
        assertThat(deleted.statusCode()).isEqualTo(204);
    }

    @Test
    void unknownPermissionCodesAreRejected() {
        HttpResponse<String> response = api.post(base() + "/roles", "{\"name\":\"Bad\",\"permissions\":[\"everything:all\"]}", owner.auth());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("unknown_permission");
    }

    private HttpResponse<String> invite(Session actor, String email, UUID roleId) {
        return api.post(base() + "/invitations", Json.object("email", email, "roleId", roleId.toString()), bearer(actor.accessToken()));
    }

    private String base() {
        return "/admin/v1/stores/" + owner.storeId();
    }

    private static String code(HttpResponse<String> response) {
        return Json.parse(response.body()).get("code").asString();
    }
}
