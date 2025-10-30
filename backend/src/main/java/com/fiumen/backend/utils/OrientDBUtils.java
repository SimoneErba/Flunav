package com.fiumen.backend.utils;

import java.util.NoSuchElementException;

import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.record.OElement;
import com.orientechnologies.orient.core.record.OVertex;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

public class OrientDBUtils {

	private OrientDBUtils() {
		// Private constructor to prevent instantiation
	}

	/**
	 * Helper method to load and validate an OElement as a vertex.
	 *
	 * @param db the database session
	 * @param id the name of the element
	 * @return the loaded and validated OElement
	 * @throws IllegalArgumentException if the element is not a valid vertex
	 */
	public static OVertex loadAndValidateVertexByCustomId(ODatabaseSession db, String id) {
		String statement = "SELECT * FROM V WHERE customId = ?";
		OResultSet rs = db.query(statement, id);
		if (rs.hasNext()) {
			OResult row = rs.next();
			OElement element = row.toElement();

			if (element == null || !element.isVertex()) {
				throw new IllegalArgumentException(
						String.format("Provided object with id %s is not a valid vertex.", id));
			}
			return element.asVertex().get();
		} else {
			throw new NoSuchElementException(String.format("No vertex found with id %s", id));
		}
	}

	public static boolean checkIfAlreadyExists(ODatabaseSession db, String name) {
		String statement = "SELECT * FROM V WHERE customId = ?";
		OResultSet rs = db.query(statement, name);
		return rs.hasNext();
	}
}