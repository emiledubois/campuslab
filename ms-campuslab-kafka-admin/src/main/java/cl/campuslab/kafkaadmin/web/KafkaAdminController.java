package cl.campuslab.kafkaadmin.web;

import cl.campuslab.kafkaadmin.kafka.ConsumerGroupInfo;
import cl.campuslab.kafkaadmin.kafka.ConsumerGroupInspectionService;
import cl.campuslab.kafkaadmin.kafka.DltInfo;
import cl.campuslab.kafkaadmin.kafka.DltInspectionService;
import cl.campuslab.kafkaadmin.kafka.TopicInfo;
import cl.campuslab.kafkaadmin.kafka.TopologyInspectionService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role enforcement itself lives in the path rule in SecurityConfig (A01) - ADMIN-only,
 * no secondary read role (design doc §3/§7 A01). Read-only: no requeue-from-DLT
 * endpoint exists this slice (design doc §3's explicit scope boundary).
 */
@RestController
@RequestMapping("/api/admin/kafka")
public class KafkaAdminController {

    private final TopologyInspectionService topologyInspectionService;
    private final ConsumerGroupInspectionService consumerGroupInspectionService;
    private final DltInspectionService dltInspectionService;

    public KafkaAdminController(
            TopologyInspectionService topologyInspectionService,
            ConsumerGroupInspectionService consumerGroupInspectionService,
            DltInspectionService dltInspectionService) {
        this.topologyInspectionService = topologyInspectionService;
        this.consumerGroupInspectionService = consumerGroupInspectionService;
        this.dltInspectionService = dltInspectionService;
    }

    @GetMapping("/topics")
    public List<TopicInfo> topics() {
        return topologyInspectionService.listTopics();
    }

    @GetMapping("/consumer-groups")
    public List<ConsumerGroupInfo> consumerGroups() {
        return consumerGroupInspectionService.listGroups();
    }

    @GetMapping("/dlt")
    public List<DltInfo> dlt() {
        return dltInspectionService.listDlts();
    }
}
