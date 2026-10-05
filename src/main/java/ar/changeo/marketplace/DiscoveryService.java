package ar.changeo.marketplace;

import ar.changeo.identity.IdentityFailure;
import ar.changeo.identity.api.JobContext.FulfillmentMode;
import ar.changeo.moderation.ModerationFailure;
import ar.changeo.moderation.api.ContentReference;
import java.util.*;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
public class DiscoveryService {
    private static final int PAGE_SIZE=10;
    private static final int CANDIDATE_LIMIT=200;
    private final MarketplaceStore store;
    private final MarketplaceReader reader;
    private final TransactionTemplate transaction;

    DiscoveryService(MarketplaceStore store, MarketplaceReader reader, PlatformTransactionManager transactions) {
        this.store=store; this.reader=reader; this.transaction=new TransactionTemplate(transactions);
    }

    public Results search(Query query) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new ModerationFailure("La búsqueda requiere transacciones actuales independientes"); }
        List<Object> arguments=new ArrayList<>();
        String sql="select r.id,r.subject_id,r.approved_revision,v.digest,s.label,ts_rank_cd(v.search_vector,websearch_to_tsquery('spanish',?)) as relevance from marketplace_resource r join marketplace_revision v on v.id=r.approved_revision join moderation_reference b on b.resource_id=r.id and b.owner_kind=? join moderation_snapshot p on p.reference_id=b.id and p.revision_id=v.id join moderation_submission s on s.id=p.id where r.type=? and r.lifecycle='OPEN' and s.state='APPROVED' and not s.recalled";
        arguments.add(query.text()); arguments.add(query.type().surface().name()); arguments.add(query.type().name());
        if (!query.text().isBlank()) { sql+=" and v.search_vector @@ websearch_to_tsquery('spanish',?)"; arguments.add(query.text()); }
        if (!query.tag().isBlank()) { sql+=" and v.tags @> ?"; arguments.add(new SqlArrayValue("text",query.tag().toLowerCase(Locale.ROOT))); }
        if (!query.province().isBlank()) { sql+=" and v.province=?"; arguments.add(query.province()); }
        if (!query.area().isBlank()) { sql+=" and lower(v.area)=lower(?)"; arguments.add(query.area()); }
        if (query.mode().isPresent()) { sql+=" and v.modes @> ?"; arguments.add(new SqlArrayValue("text",query.mode().orElseThrow().name())); }
        sql+=" order by relevance desc,r.id limit "+CANDIDATE_LIMIT;
        var candidates=store.jdbc.query(sql,(row,index) -> new MarketplaceReader.Selection(new ContentReference(query.type().surface(),row.getObject("id",UUID.class),row.getObject("approved_revision",UUID.class)),row.getObject("subject_id",UUID.class),row.getString("digest"),row.getString("label")),arguments.toArray());
        List<MarketplaceReader.PublicView> visible=new ArrayList<>();
        int wanted=(query.page()+1)*PAGE_SIZE+1;
        for (var candidate:candidates) {
            try {
                var view=transaction.execute(status -> reader.currentInTransaction(candidate.reference().resourceId(),Optional.of(candidate)));
                visible.add(Objects.requireNonNull(view));
                if (visible.size()>=wanted) { break; }
            } catch (ModerationFailure | IdentityFailure denied) { }
        }
        int from=Math.min(query.page()*PAGE_SIZE,visible.size());
        int until=Math.min(from+PAGE_SIZE,visible.size());
        return new Results(query,List.copyOf(visible.subList(from,until)),visible.size()>until);
    }

    public record Query(PresenceType type, String text, String tag, String province, String area, Optional<FulfillmentMode> mode, int page) {
        public Query {
            Objects.requireNonNull(type); Objects.requireNonNull(text); Objects.requireNonNull(tag); Objects.requireNonNull(province); Objects.requireNonNull(area); Objects.requireNonNull(mode);
            if (type.profile() || text.length()>100 || tag.length()>40 || province.length()>80 || area.length()>80 || page<0 || page>9
                    || (!province.isBlank() && !MarketplaceDetails.PROVINCES.contains(province))) { throw new ModerationFailure("Filtros inválidos o búsqueda demasiado amplia; revisá y probá otra vez"); }
            text=text.trim(); tag=tag.trim(); province=province.trim(); area=area.trim();
        }
    }
    public record Results(Query query, List<MarketplaceReader.PublicView> items, boolean hasMore) {}
}
