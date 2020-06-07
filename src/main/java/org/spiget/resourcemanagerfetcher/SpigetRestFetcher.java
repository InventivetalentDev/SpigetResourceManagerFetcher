package org.spiget.resourcemanagerfetcher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mongodb.client.FindIterable;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.bson.Document;
import org.jetbrains.annotations.Nullable;
import org.spiget.client.json.JsonClient;
import org.spiget.client.json.JsonResponse;
import org.spiget.data.UpdateRequest;
import org.spiget.data.author.Author;
import org.spiget.data.resource.Rating;
import org.spiget.data.resource.Resource;
import org.spiget.data.resource.SpigetIcon;
import org.spiget.database.DatabaseClient;
import org.spiget.database.DatabaseParser;
import org.spiget.database.SpigetGson;

import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Log4j2
public class SpigetRestFetcher {

	public static JsonObject config;

	public static DatabaseClient databaseClient;

	int  itemsPerFetch = 500;
	long delay         = 1000;
	int start =0;

	@Nullable
	public SpigetRestFetcher init() throws IOException {
		config = new JsonParser().parse(new FileReader("config.json")).getAsJsonObject();

		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				try {
					log.info("Disconnecting database...");
					databaseClient.disconnect();
				} catch (IOException e) {
					log.warn("Failed to disconnect from database", e);
				}
			}
		});

		{
			log.info("Initializing & testing database connection...");
			long testStart = System.currentTimeMillis();
			try {
				databaseClient = new DatabaseClient(
						config.get("database.name").getAsString(),
						config.get("database.host").getAsString(),
						config.get("database.port").getAsInt(),
						config.get("database.user").getAsString(),
						config.get("database.pass").getAsString().toCharArray(),
						config.get("database.db").getAsString());
				databaseClient.connect(config.get("database.timeout").getAsInt());
				databaseClient.collectionCount();
				log.info("Connection successful (" + (System.currentTimeMillis() - testStart) + "ms)");
			} catch (Exception e) {
				log.fatal("Connection failed after " + (System.currentTimeMillis() - testStart) + "ms", e);
				log.fatal("Aborting.");

				System.exit(-1);
				return null;
			}
		}

		{
			log.info("Testing SpigotMC connection...");
			long testStart = System.currentTimeMillis();
			try {
				JsonResponse response = JsonClient.get("https://api.spigotmc.org/simple/0.1/index.php?action=getResource&id=2");
				if (response == null) { return null; }
				int code = response.code;
				if (code >= 200 && code < 400) {
					log.info("Connection successful (" + (System.currentTimeMillis() - testStart) + "ms)");
				} else {
					log.fatal("Connection failed with code " + code + " after " + (System.currentTimeMillis() - testStart) + "ms");
					log.fatal("Aborting.");
					log.info(response.json);

					System.exit(-1);
					return null;
				}
			} catch (Exception e) {
				log.fatal("Connection failed after " + (System.currentTimeMillis() - testStart) + "ms", e);
				log.fatal("Aborting.");

				System.exit(-1);
				return null;
			}
		}

		itemsPerFetch = config.get("database.itemsPerFind").getAsInt();
		JsonClient.userAgent = config.get("request.userAgent").getAsString();
		JsonClient.logConn = config.get("debug.connections").getAsBoolean();
		delay = config.get("fetch.pause").getAsLong();
		start = config.get("fetch.start").getAsInt();

		return this;
	}

	public void fetch() {
		long startTime = System.currentTimeMillis();

		try {
			databaseClient.updateStatus("fetch.rest.start", startTime);
			long lastEnd = databaseClient.getStatus("fetch.rest.end", 0L);
			databaseClient.updateStatus("fetch.rest.lastEnd", lastEnd);
			databaseClient.updateStatus("fetch.rest.end", 0);
		} catch (Exception e) {
			log.log(Level.ERROR, "Failed to update status", e);
		}

		int n = start;
		int c = 0;
		while ((c = fetchN(n)) >= itemsPerFetch) {
			n++;
		}

		long endTime  = System.currentTimeMillis();
		try {
			databaseClient.updateStatus("fetch.rest.end", endTime);
			databaseClient.updateStatus("fetch.rest.duration", (endTime - startTime));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public int fetchN(int n) {
		log.info("Running Fetch #" + n);
		int c = 0;

		try {
			databaseClient.updateStatus("fetch.rest.type", "resource");
			databaseClient.updateStatus("fetch.rest.num", n);
			databaseClient.updateStatus("fetch.rest.item.max", itemsPerFetch);


			//// RESOURCES


			FindIterable<Document> iterable = databaseClient.getResourcesCollection().find().sort(new Document("updateDate", 1)).limit(itemsPerFetch).skip(n * itemsPerFetch);
			long updateStart = System.currentTimeMillis();

			databaseClient.updateStatus("fetch.rest.n.start", updateStart);

			for (Document document : iterable) {
				c++;

				log.info("R F" + n + " I" + c);

				try {
					databaseClient.updateStatus("fetch.rest.item", c);

					try {
						Thread.sleep(delay);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					Resource resource = SpigetGson.RESOURCE.fromJson(DatabaseParser.toJson(document), Resource.class);
					JsonResponse response = JsonClient.get("https://api.spigotmc.org/simple/0.1/index.php?action=getResource&id=" + resource.getId());
					if (response == null) { continue; }
					if (response.code != 200) {
						log.warn("Got Code " + response.code + " for getResource #" + resource.getId());
						if (response.code == 503) {// Cloudflare
						} else if (response.code == 404) {// Resource not found
							log.info("Scheduling #" + resource.getId() + " for deletion");
							requestUpdate(resource.getId(), true);
						} else {
							log.error("Unexpected status code");
						}
					} else {
						if (!response.json.isJsonObject()) {
							log.warn("Expected response to be a json object but was not.");
							log.warn(response.json);
						} else {
							JsonObject json = response.json.getAsJsonObject();

							String title = json.get("title").getAsString();
							if (resource.getName() != null && title != null && !resource.getName().equals(title)) {// name changed
								log.info("Name of #" + resource.getId() + " changed  \"" + resource.getName() + "\" -> \"" + title + "\"");
								updateName(resource.getId(), title);
							}

							String tag = json.get("tag").getAsString();
							if (resource.getTag() != null && tag != null && !resource.getTag().equals(tag)) {// tag changed
								log.info("Tag of #" + resource.getId() + " changed  \"" + resource.getTag() + "\" -> \"" + tag + "\"");
								updateTag(resource.getId(), tag);
							}

							boolean requestUpdate = false;

							JsonObject statsJson = json.get("stats").getAsJsonObject();
							if (statsJson != null) {
								int downloads = statsJson.get("downloads").getAsInt();
								if (resource.getDownloads() != downloads) {
									log.info("Downloads of #" + resource.getId() + " changed  " + resource.getDownloads() + " -> " + downloads);
									updateDownloads(resource.getId(), downloads);
								}

								Rating rating = resource.getRating();
								if (rating != null) {
									int ratingCount = statsJson.get("reviews").getAsInt();
									float ratingAvg = statsJson.get("rating").getAsFloat();
									if (ratingCount > rating.getCount()) {
										//									requestUpdate = true;
										updateRatingCount(resource.getId(), ratingCount);
									}
									if (ratingAvg != rating.getAverage()) {
										//									requestUpdate = true;
										updateRatingAvg(resource.getId(), ratingAvg);
									}
								}

								int updates = statsJson.get("updates").getAsInt();
								if (updates > resource.getUpdates().size()) {
									//								requestUpdate = true;
								}
							}

							if (requestUpdate) {
								log.info("Requesting update for #" + resource.getId());
								requestUpdate(resource.getId(), false);
							}

						}
					}
				} catch (Exception e) {
					log.log(Level.WARN, "Exception while trying to check resource", e);
				}
			}



			//// AUTHORS


			databaseClient.updateStatus("fetch.rest.type", "author");

			 iterable = databaseClient.getAuthorsCollection().find().sort(new Document("_id", 1)).limit(itemsPerFetch).skip(n * itemsPerFetch);

			for (Document document : iterable) {
				c++;

				log.info("A F" + n + " I" + c);

				try {
					databaseClient.updateStatus("fetch.rest.item", c);

					try {
						Thread.sleep(delay);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					Author author = SpigetGson.AUTHOR.fromJson(DatabaseParser.toJson(document), Author.class);
					JsonResponse response = JsonClient.get("https://api.spigotmc.org/simple/0.1/index.php?action=getAuthor&id=" + author.getId());
					if (response == null) { continue; }
					if (response.code != 200) {
						log.warn("Got Code " + response.code + " for getAuthor #" + author.getId());
						if (response.code == 503) {// Cloudflare
						} else if (response.code == 404) {// Author not found
							// disabled deletion again, since the data returned from the api seems really inconsistent
							// i.e. members which do exist frequently return 404s
//							log.info("Scheduling #" + author.getId() + " for deletion");
//							requestUpdate(author.getId(), true);
//							deleteAuthor(author.getId());
						} else {
							log.error("Unexpected status code");
						}
					} else {
						if (!response.json.isJsonObject()) {
							log.warn("Expected response to be a json object but was not.");
							log.warn(response.json);
						} else {
							JsonObject json = response.json.getAsJsonObject();

							String username = json.get("username").getAsString();
							if (author.getName() != null && username != null && !author.getName().equals(username)) {// name changed
								log.info("Username of #" + author.getId() + " changed  \"" + author.getName() + "\" -> \"" + username + "\"");
								updateUserName(author.getId(), username);
							}

							JsonElement identitiesEl = json.get("identities");
							if (identitiesEl.isJsonObject()) {
								JsonObject identities = identitiesEl.getAsJsonObject();
								Map identityMap = new HashMap();
								if (author.getIdentities() != null) {
									identityMap.putAll(author.getIdentities());
								}
								if (identities != null) {
									boolean changed = false;
									for (Map.Entry<String, JsonElement> entry : identities.entrySet()) {
										if (!identityMap.containsKey(entry.getKey()) || (entry.getValue() != null && !entry.getValue().getAsString().equals(author.getIdentities().get(entry.getKey())))) {
											//noinspection unchecked
											identityMap.put(entry.getKey(), entry.getValue().getAsString());
											log.info(entry.getKey() + " identity of #" + author.getId() + " changed  \"" + identityMap.get(entry.getKey()) + "\" -> \"" + entry.getValue().getAsString() + "\"");
											changed = true;
										}
									}
									if (changed) {
										//noinspection unchecked
										updateIdentities(author.getId(), identityMap);
									}
								}
							}

							JsonObject avatar = json.get("avatar").getAsJsonObject();
							if (avatar != null && author.getIcon() != null) {
								SpigetIcon icon = author.getIcon();
								if ((avatar.get("info") != null && !avatar.get("info").getAsString().equals(icon.getInfo())) || (avatar.get("hash") != null && !avatar.get("hash").getAsString().equals(icon.getHash()))) {
									updateAvatar(author.getId(), avatar.get("info").getAsString(), avatar.get("hash").getAsString());
								}
							}
						}
					}
				} catch (Exception e) {
					log.log(Level.WARN, "Exception while trying to check resource", e);
				}
			}



			long updateEnd =System.currentTimeMillis();
			databaseClient.updateStatus("fetch.rest.n.end", updateEnd);
			databaseClient.updateStatus("fetch.rest.n.duration", (updateEnd - updateStart));

			log.log(Level.INFO, "Finished fetch #"+n+". Took " + (((double) updateEnd - updateStart) / 1000.0 / 60.0) + " minutes to update " + c + " resources.");
		} catch (Exception e) {
			log.log(Level.ERROR, "Exception in fetch #" + n, e);
		}

		return c;
	}

	// From  https://github.com/SpiGetOrg/SpigetExistence/blob/master/src/main/java/org/spiget/existence/SpigetExistence.java#L201

	void setResourceStatus(int id, int status) {
		databaseClient.getResourcesCollection().updateOne(new Document("_id", id), new Document("$set", new Document("existenceStatus", status)));
	}

	void updateName(int id, String name) {
		databaseClient.getResourcesCollection().updateOne(new Document("_id", id), new Document("$set", new Document("name", name).append("fetch.restLatest", System.currentTimeMillis())));
	}

	void updateTag(int id, String tag) {
		databaseClient.getResourcesCollection().updateOne(new Document("_id", id), new Document("$set", new Document("tag", tag).append("fetch.restLatest", System.currentTimeMillis())));
	}

	void updateDownloads(int id, int downloads) {
		databaseClient.getResourcesCollection().updateOne(new Document("_id", id), new Document("$set", new Document("downloads", downloads).append("fetch.restLatest", System.currentTimeMillis())));
	}

	void updateRatingCount(int id, int count) {
		databaseClient.getResourcesCollection().updateOne(new Document("_id", id), new Document("$set", new Document("rating.count", count).append("fetch.restLatest", System.currentTimeMillis())));
	}

	void updateRatingAvg(int id, float rating) {
		databaseClient.getResourcesCollection().updateOne(new Document("_id", id), new Document("$set", new Document("rating.average", rating).append("fetch.restLatest", System.currentTimeMillis())));
	}

	void updateUserName(int id, String name) {
		databaseClient.getAuthorsCollection().updateOne(new Document("_id", id), new Document("$set", new Document("name", name).append("fetch.restLatest", System.currentTimeMillis())));
	}

	void updateIdentities(int id, Map<String, Object> identities) {
		databaseClient.getAuthorsCollection().updateOne(new Document("_id", id), new Document("$set", new Document("identities", new Document(identities)).append("fetch.restLatest", System.currentTimeMillis())));
	}

	void updateAvatar(int id, String info, String hash) {
		databaseClient.getAuthorsCollection().updateOne(new Document("_id", id), new Document("$set", new Document("icon.info", info).append("icon.hash", hash).append("fetch.restLatest", System.currentTimeMillis())));
	}

	void deleteAuthor(int id) {
		databaseClient.getAuthorsCollection().updateOne(new Document("_id", id), new Document("$set", new Document("shouldDelete", true)));
	}

	void requestUpdate(int id) {
		requestUpdate(id, false);
	}

	void requestUpdate(int id, boolean shouldDelete) {
		requestUpdate(id, shouldDelete, true, true, true);
	}

	void requestUpdate(int id, boolean shouldDelete, boolean versions, boolean updates, boolean reviews) {
		UpdateRequest request = new UpdateRequest();
		request.setType("resource");
		request.setDelete(shouldDelete);
		request.setVersions(versions);
		request.setUpdates(updates);
		request.setReviews(reviews);
		request.setRequestedId(id);
		request.setRequested(System.currentTimeMillis());

		Document document = DatabaseParser.toDocument(SpigetGson.UPDATE_REQUEST.toJsonTree(request));
		databaseClient.getUpdateRequestsCollection().insertOne(document);
	}

}
