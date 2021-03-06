var designDoc = {
	"_id": "_design/Answer",
	"language": "javascript",
	"views": {
		"by_id": {
			"map": function (doc) {
				if (doc.type === "Answer") {
					emit(doc._id, {_rev: doc._rev});
				}
			},
			"reduce": "_count"
		},
		"by_contentid": {
			"map": function (doc) {
				if (doc.type === "Answer") {
					emit(doc.contentId, {_rev: doc._rev});
				}
			},
            "reduce": "_count"
		},
		"by_contentid_round_body_subject": {
			"map": function (doc) {
				if (doc.type === "Answer") {
					emit([doc.contentId, doc.round, doc.abstention, doc.body, doc.subject, doc.successfulFreeTextAnswer], {_rev: doc._rev});
				}
			},
			"reduce": "_count"
		},
		"by_contentid_round_selectedchoiceindexes": {
			"map": function (doc) {
				if (doc.type === "Answer") {
					emit([doc.contentId, doc.round, doc.selectedChoiceIndexes], {_rev: doc._rev});
				}
			},
			"reduce": "_count"
		},
		"by_contentid_creationtimestamp": {
			"map": function (doc) {
				if (doc.type === "Answer") {
					emit([doc.contentId, doc.creationTimestamp], {_rev: doc._rev});
				}
			}
		},
		"by_contentid_creatorid_round": {
			"map": function (doc) {
				if (doc.type === "Answer") {
					emit([doc.contentId, doc.creatorId, doc.round], {_rev: doc._rev});
				}
			}
		},
		"by_roomid": {
			"map": function (doc) {
				if (doc.type === "Answer") {
					emit(doc.roomId, {_rev: doc._rev});
				}
			},
			"reduce": "_count"
		},
		"by_creatorid_roomid": {
			"map": function (doc) {
				if (doc.type === "Answer") {
					emit([doc.creatorId, doc.roomId], {_rev: doc._rev});
				}
			}
		}
	}
};
