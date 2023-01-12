package com.michelin.ns4kafka.controllers;

import com.michelin.ns4kafka.controllers.generic.NamespacedResourceController;
import com.michelin.ns4kafka.models.KafkaStream;
import com.michelin.ns4kafka.models.Namespace;
import com.michelin.ns4kafka.services.StreamService;
import com.michelin.ns4kafka.utils.enums.ApplyStatus;
import com.michelin.ns4kafka.utils.exceptions.ResourceValidationException;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;

import javax.validation.Valid;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Tag(name = "Streams")
@Controller(value = "/api/namespaces/{namespace}/streams")
public class StreamController extends NamespacedResourceController {
    @Inject
    StreamService streamService;

    /**
     * Get all the Kafka Streams by namespace
     * @param namespace The namespace
     * @return A list of Kafka Streams
     */
    @Get("/")
    List<KafkaStream> list(String namespace){
        Namespace ns = getNamespace(namespace);
        return streamService.findAllForNamespace(ns);

    }

    /**
     * Get a Kafka Streams by namespace and name
     * @param namespace The name
     * @param stream The Kafka Streams name
     * @return The Kafka Streams
     */
    @Get("/{stream}")
    Optional<KafkaStream> get(String namespace, String stream){
        Namespace ns = getNamespace(namespace);
        return streamService.findByName(ns, stream);

    }

    /**
     * Create a Kafka Streams
     * @param namespace The namespace
     * @param stream The Kafka Stream
     * @param dryrun Is dry run mode or not ?
     * @return An HTTP response
     */
    @Post("/{?dryrun}")
    HttpResponse<KafkaStream> apply(String namespace,@Body @Valid KafkaStream stream, @QueryValue(defaultValue = "false") boolean dryrun){
        Namespace ns = getNamespace(namespace);
        if (!streamService.isNamespaceOwnerOfKafkaStream(ns, stream.getMetadata().getName())) {
            throw new ResourceValidationException(List.of("Invalid value " + stream.getMetadata().getName()
                    + " for name: Namespace not OWNER of underlying Topic prefix and Group prefix"), "Stream", stream.getMetadata().getName());
        }

        // Augment the Stream
        stream.getMetadata().setCreationTimestamp(Date.from(Instant.now()));
        stream.getMetadata().setCluster(ns.getMetadata().getCluster());
        stream.getMetadata().setNamespace(ns.getMetadata().getName());

        // Creation of the correct ACLs
        Optional<KafkaStream> existingStream = streamService.findByName(ns, stream.getMetadata().getName());
        if (existingStream.isPresent() && existingStream.get().equals(stream)){
            return formatHttpResponse(stream, ApplyStatus.unchanged);
        }

        ApplyStatus status = existingStream.isPresent() ? ApplyStatus.changed : ApplyStatus.created;

        if (dryrun) {
            return formatHttpResponse(stream, status);
        }

        sendEventLog(stream.getKind(),
                stream.getMetadata(),
                status,
                null,
                null);

        return formatHttpResponse(streamService.create(stream), status);
    }

    /**
     * Delete a Kafka Streams
     * @param namespace The namespace
     * @param stream The Kafka Streams
     * @param dryrun Is dry run mode or not ?
     * @return An HTTP response
     */
    @Status(HttpStatus.NO_CONTENT)
    @Delete("/{stream}{?dryrun}")
    HttpResponse<Void> delete(String namespace,String stream, @QueryValue(defaultValue = "false") boolean dryrun){
        Namespace ns = getNamespace(namespace);
        if (!streamService.isNamespaceOwnerOfKafkaStream(ns, stream)) {
            throw new ResourceValidationException(List.of("Invalid value " + stream
                    + " for name: Namespace not OWNER of underlying Topic prefix and Group prefix"), "Stream", stream);
        }

        Optional<KafkaStream> optionalStream = streamService.findByName(ns, stream);

        if (optionalStream.isEmpty()) {
            return HttpResponse.notFound();
        }

        if (dryrun) {
            return HttpResponse.noContent();
        }

        var streamToDelete = optionalStream.get();
        sendEventLog(streamToDelete.getKind(),
                streamToDelete.getMetadata(),
                ApplyStatus.deleted,
                null,
                null);
        streamService.delete(ns, optionalStream.get());
        return HttpResponse.noContent();
    }
}