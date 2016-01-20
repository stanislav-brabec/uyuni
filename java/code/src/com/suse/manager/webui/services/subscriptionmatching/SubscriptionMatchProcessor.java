/**
 * Copyright (c) 2016 SUSE LLC
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package com.suse.manager.webui.services.subscriptionmatching;

import com.redhat.rhn.taskomatic.TaskoFactory;
import com.redhat.rhn.taskomatic.TaskoRun;
import com.suse.matcher.json.JsonInput;
import com.suse.matcher.json.JsonMessage;
import com.suse.matcher.json.JsonOutput;
import com.suse.matcher.json.JsonSubscription;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * Processes data from the matcher to a form that's displayable by the UI.
 */
public class SubscriptionMatchProcessor {

    /**
     * Gets UI-ready data.
     *
     * @param input matcher input
     * @param output matcher output
     * @return the data
     */
    public Object getData(Optional<JsonInput> input, Optional<JsonOutput> output) {
        TaskoRun latestRun = TaskoFactory.getLatestRun("gatherer-matcher-bunch");
        Date latestStart = latestRun == null ? null : latestRun.getStartTime();
        Date latestEnd = latestRun == null ? null : latestRun.getEndTime();
        if (input.isPresent() && output.isPresent()) {
            MatcherUiData matcherUiData = new MatcherUiData(true,
                    latestStart,
                    latestEnd,
                    messages(input.get(), output.get()),
                    subscriptions(input.get(), output.get()));
            return matcherUiData;
        }
        else {
            return new MatcherUiData(false, latestStart, latestEnd, new LinkedList<>(),
                    new LinkedList<>());
        }
    }

    private List<JsonMessage> messages(JsonInput input, JsonOutput output) {
        return output.getMessages().stream()
                .map(m -> adjustMessage(m, input))
                .collect(Collectors.toList());
    }

    private List<Subscription> subscriptions(JsonInput input, JsonOutput output) {
        Map<Long, Integer> matchedQuantity = matchedQuantity(output);
        return input.getSubscriptions().stream()
                .map(js -> new Subscription(js.getPartNumber(),
                        js.getName(),
                        js.getQuantity(),
                        matchedQuantity.getOrDefault(js.getId(), 0),
                        js.getStartDate(), js.getEndDate()))
                .filter(s -> s.getTotalQuantity() > 0)
                .sorted((s1, s2) -> s2.getEndDate().compareTo(s1.getEndDate()))
                .collect(Collectors.toList());
    }

    private Map<Long, Integer> matchedQuantity(JsonOutput output) {
        // check what about ids which are in input, but not in output (currently we set them
        // to 0)
        // compute cents by subscription id
        Map<Long, Integer> matchedCents = new HashMap<>();
        Map<Long, Integer> matchedQuantity = new HashMap<>();
        output.getConfirmedMatches()
                .forEach(m -> matchedCents.merge(m.getSubscriptionId(), m.getCents(),
                        Math::addExact));

        matchedCents.forEach((sid, cents)
                -> matchedQuantity.put(sid, (cents + 100 - 1) / 100));

        return matchedQuantity;
    }

    private static JsonMessage adjustMessage(JsonMessage message, JsonInput input) {
        final Set<String> typesWithSystemId = new HashSet<>();
        typesWithSystemId.add("guest_with_unknown_host");
        typesWithSystemId.add("unknown_cpu_count");
        typesWithSystemId.add("physical_guest");

        Map<String, String> data = new HashMap<>();
        if (typesWithSystemId.contains(message.getType())) {
            long systemId = Long.parseLong(message.getData().get("id"));
            data.put("name", ofNullable(
                    input.getSystems().stream()
                            .filter(s -> s.getId().equals(systemId))
                            .findFirst()
                            .get().getName())
                    .orElse("System id: " + systemId));
            return new JsonMessage(message.getType(), data);
        }
        else if (message.getType().equals("unsatisfied_pinned_match")) {
            long systemId = Long.parseLong(message.getData().get("system_id"));
            data.put("system_name", ofNullable(
                    input.getSystems().stream()
                            .filter(s -> s.getId().equals(systemId))
                            .findFirst()
                            .get().getName())
                    .orElse("System id: " + systemId));
            long subscriptionId = Long.parseLong(message.getData().get("subscription_id"));
            data.put("subscription_name", subscriptionNameById(input.getSubscriptions(),
                    subscriptionId));
            return new JsonMessage(message.getType(), data);
        }
        else { // pass it through
            return new JsonMessage(message.getType(), message.getData());
        }
    }

    private static String subscriptionNameById(List<JsonSubscription> subscriptions,
            Long id) {
        return ofNullable(subscriptions.stream()
                .filter(s -> s.getId().equals(id))
                .findFirst()
                .get().getName()).orElse("Subscription id: " + id);
    }
}
