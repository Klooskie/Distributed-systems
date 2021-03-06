package bookshop;

import akka.actor.*;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.pf.DeciderBuilder;
import requests.CheckBookPriceRequest;
import responses.CheckBookPriceResponse;
import scala.concurrent.duration.Duration;

import java.io.FileNotFoundException;

import static akka.actor.SupervisorStrategy.*;

public class CheckBookPriceActor extends AbstractActor {

    private ActorRef clientActor;
    private String firstBooksDatabasePath;
    private String secondBooksDatabasePath;

    private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

    private ActorRef firstDatabaseSearchActor;
    private ActorRef secondDatabaseSearchActor;
    private int databasesSearched = 0;
    private boolean responseSent = false;

    public CheckBookPriceActor(ActorRef clientActor, String firstBooksDatabasePath, String secondBooksDatabasePath) {
        this.clientActor = clientActor;
        this.firstBooksDatabasePath = firstBooksDatabasePath;
        this.secondBooksDatabasePath = secondBooksDatabasePath;
    }

    @Override
    public AbstractActor.Receive createReceive() {
        return receiveBuilder()
                .match(CheckBookPriceRequest.class, checkBookPriceRequest -> {
                    log.info("got checkBookPriceRequest " + checkBookPriceRequest.getBookTitle());

                    // ustalenie nazw aktorów do przeszukiwania bazy
                    String firstDatabaseSearchActorName = "first_database_search_actor";
                    String secondDatabaseSearchActorName = "second_database_search_actor";

                    // stworzenie aktora do przeszukiwania pierwszej bazy i zlecenie mu requesta
                    this.firstDatabaseSearchActor = context()
                            .actorOf(Props.create(SearchDatabaseActor.class, this.firstBooksDatabasePath),
                                    firstDatabaseSearchActorName);
                    this.firstDatabaseSearchActor.tell(checkBookPriceRequest, getSelf());

                    // stworzenie aktora do przeszukiwania drugiej bazy i zlecenie mu requesta
                    this.secondDatabaseSearchActor = context()
                            .actorOf(Props.create(SearchDatabaseActor.class, this.secondBooksDatabasePath),
                                    secondDatabaseSearchActorName);
                    this.secondDatabaseSearchActor.tell(checkBookPriceRequest, getSelf());

                })
                .match(CheckBookPriceResponse.class, checkBookPriceResponse -> {

                    this.databasesSearched++;

                    if ((this.databasesSearched == 2 && !this.responseSent) || (checkBookPriceResponse.isBookFound() && !this.responseSent)) {
                        this.clientActor.tell(checkBookPriceResponse, getSelf());
                        this.responseSent = true;

                        // pozabijaj podwladnych i siebie
                        this.firstDatabaseSearchActor.tell(PoisonPill.getInstance(), getSelf());
                        this.secondDatabaseSearchActor.tell(PoisonPill.getInstance(), getSelf());
                        getSelf().tell(PoisonPill.getInstance(), getSelf());
                    }

                })
                .matchAny(o -> log.info("Received unknown message"))
                .build();
    }


    @Override
    public SupervisorStrategy supervisorStrategy() {
        return new OneForOneStrategy(10, Duration.create(1, "minute"),
                DeciderBuilder
                        .match(FileNotFoundException.class, e -> {
                            getSelf().tell(new CheckBookPriceResponse(false, -1), getSelf());
                            return SupervisorStrategy.resume();
                        })
                        .matchAny(o -> {
                            getSelf().tell(new CheckBookPriceResponse(false, -1), getSelf());
                            return SupervisorStrategy.resume();
                        })
                        .build()
        );
    }
}
