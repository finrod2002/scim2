/*
 * Copyright 2015 UnboundID Corp.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPLv2 only)
 * or the terms of the GNU Lesser General Public License (LGPLv2.1 only)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses>.
 */

package com.unboundid.scim2.common.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ValueNode;
import com.fasterxml.jackson.databind.util.ISO8601Utils;
import com.unboundid.scim2.common.Path;
import com.unboundid.scim2.common.exceptions.BadRequestException;
import com.unboundid.scim2.common.exceptions.ScimException;
import com.unboundid.scim2.common.filters.Filter;
import com.unboundid.scim2.common.messages.PatchOperation;
import com.unboundid.scim2.common.types.AttributeDefinition;

import java.text.ParseException;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;

/**
 * Utility methods to manipulate JSON nodes using paths.
 */
public class JsonUtils
{
  private static final ObjectMapper SDK_OBJECT_MAPPER = createObjectMapper();
  private abstract static class NodeVisitor
  {
    /**
     * Visit a node referenced by an path element before that last element.
     *
     * @param parent The parent container ObjectNode.
     * @param element The path element.
     * @return The JsonNode referenced by the element in the parent.
     * @throws ScimException If an error occurs.
     */
    abstract JsonNode visitInnerNode(final ObjectNode parent,
                                     final Path.Element element)
        throws ScimException;

    /**
     * Visit a node referenced by the last path element.
     *
     * @param parent The parent container ObjectNode.
     * @param element The path element.
     * @throws ScimException If an error occurs.
     */
    abstract void visitLeafNode(final ObjectNode parent,
                                final Path.Element element)
        throws ScimException;

    /**
     *
     * @param array The ArrayNode to filter.
     * @param valueFilter The value filter.
     * @param removeMatching {@code true} to remove matching values or
     *                       {@code false} otherwise.
     * @return The matching values.
     * @throws ScimException If an error occurs.
     */
    ArrayNode filterArray(final ArrayNode array, final Filter valueFilter,
                          final boolean removeMatching)
        throws ScimException
    {
      ArrayNode matchingArray = getJsonNodeFactory().arrayNode();
      Iterator<JsonNode> i = array.elements();
      while(i.hasNext())
      {
        JsonNode node = i.next();
        if(FilterEvaluator.evaluate(valueFilter, node))
        {
          matchingArray.add(node);
          if(removeMatching)
          {
            i.remove();
          }
        }
      }
      return matchingArray;
    }
  }


  private static final class GatheringNodeVisitor extends NodeVisitor
  {
    final List<JsonNode> values = new LinkedList<JsonNode>();
    final boolean removeValues;

    /**
     * Create a new GatheringNodeVisitor.
     *
     * @param removeValues {@code true} to remove the gathered values from
     *                     the container node or {@code false} otherwise.
     */
    private GatheringNodeVisitor(final boolean removeValues)
    {
      this.removeValues = removeValues;
    }

    /**
     * {@inheritDoc}
     */
    JsonNode visitInnerNode(final ObjectNode parent,
                            final Path.Element element)
        throws ScimException
    {
      JsonNode node = parent.path(element.getAttribute());
      if(node.isArray() && element.getValueFilter() != null)
      {
        return filterArray((ArrayNode) node, element.getValueFilter(), false);
      }
      return node;
    }

    /**
     * {@inheritDoc}
     */
    void visitLeafNode(final ObjectNode parent,
                       final Path.Element element) throws ScimException
    {
      JsonNode node = parent.path(element.getAttribute());
      if(node.isArray())
      {
        ArrayNode arrayNode = (ArrayNode) node;

        if(element.getValueFilter() != null)
        {
          arrayNode = filterArray((ArrayNode) node, element.getValueFilter(),
              removeValues);
        }
        if (arrayNode.size() > 0)
        {
          values.add(arrayNode);
        }

        if(removeValues &&
            (element.getValueFilter() == null || node.size() == 0))
        {
          // There are no more values left after removing the matching values.
          // Just remove the field.
          parent.remove(element.getAttribute());
        }
      }
      else if(node.isObject() || node.isValueNode())
      {
        values.add(node);
        if(removeValues)
        {
          parent.remove(element.getAttribute());
        }
      }
    }
  }

  private static final class UpdatingNodeVisitor extends NodeVisitor
  {
    private final JsonNode value;
    private final boolean appendValues;

    /**
     * Create a new UpdatingNodeVisitor.
     *
     * @param value The update value.
     * @param appendValues {@code true} to append the update value or
     *                     {@code false} otherwise.
     */
    private UpdatingNodeVisitor(final JsonNode value,
                                final boolean appendValues)
    {
      this.value = value.deepCopy();
      this.appendValues = appendValues;
    }

    /**
     * {@inheritDoc}
     */
    JsonNode visitInnerNode(final ObjectNode parent,
                            final Path.Element element)
        throws ScimException
    {
      JsonNode node = parent.path(element.getAttribute());
      if(node.isValueNode() || ((node.isMissingNode() || node.isNull()) &&
          element.getValueFilter() != null))
      {
        throw BadRequestException.noTarget("Attribute " +
            element.getAttribute() + " does not have a multi-valued or " +
            "complex value");
      }
      if(node.isMissingNode() || node.isNull())
      {
        // Create the missing node as an JSON object node.
        ObjectNode newObjectNode = getJsonNodeFactory().objectNode();
        parent.set(element.getAttribute(), newObjectNode);
        return newObjectNode;
      }
      else if(node.isArray())
      {
        ArrayNode arrayNode = (ArrayNode) node;
        if(element.getValueFilter() != null)
        {
          arrayNode =
              filterArray((ArrayNode)node, element.getValueFilter(), false);
        }
        if(arrayNode.size() == 0)
        {
          throw BadRequestException.noTarget("Attribute " +
              element.getAttribute() + " does not have a value matching the " +
              "filter " + element.getValueFilter().toString());
        }
        return arrayNode;
      }
      return node;
    }

    /**
     * {@inheritDoc}
     */
    void visitLeafNode(final ObjectNode parent,
                       final Path.Element element)
        throws ScimException
    {
      String attributeName = null;
      if(element != null)
      {
        attributeName = element.getAttribute();
        JsonNode node = parent.path(attributeName);
        if (!appendValues && element.getValueFilter() != null)
        {
          // in replace mode, a value filter requires that the target node
          // be an array and that we can find matching value(s)
          boolean matchesFound = false;
          if (node.isArray())
          {
            for(int i = 0; i < node.size(); i++)
            {
              if(FilterEvaluator.evaluate(
                  element.getValueFilter(), node.get(i)))
              {
                matchesFound = true;
                if(node.get(i).isObject() && value.isObject())
                {
                  updateValues((ObjectNode) node.get(i), null, value);
                }
                else
                {
                  ((ArrayNode) node).set(i, value);
                }
              }
            }
          }
          if(!matchesFound)
          {
            throw BadRequestException.noTarget("Attribute " +
                element.getAttribute() + " does not have a value matching " +
                "the filter " + element.getValueFilter().toString());
          }
          return;
        }
      }
      updateValues(parent, attributeName, value);
    }

    /**
     * Update the value(s) of the field specified by the key in the parent
     * container node.
     *
     * @param parent The container node.
     * @param key The key of the field to update.
     * @param value The update value.
     */
    private void updateValues(final ObjectNode parent, final String key,
                              final JsonNode value)
    {
      if(value.isNull() || value.isArray() && value.size() == 0)
      {
        // draft-ietf-scim-core-schema section 2.4 states "Unassigned
        // attributes, the null value, or empty array (in the case of
        // a multi-valued attribute) SHALL be considered to be
        // equivalent in "state".
        return ;
      }
      // When key is null, the node to update is the parent it self.
      JsonNode node = key == null ? parent : parent.path(key);
      if(node.isObject())
      {
        if(value.isObject())
        {
          // Go through the fields of both objects and merge them.
          ObjectNode targetObject = (ObjectNode) node;
          ObjectNode valueObject = (ObjectNode) value;
          Iterator<Map.Entry<String, JsonNode>> i = valueObject.fields();
          while (i.hasNext())
          {
            Map.Entry<String, JsonNode> field = i.next();
            updateValues(targetObject, field.getKey(), field.getValue());
          }
        }
        else
        {
          // Replace the field.
          parent.set(key, value);
        }
      }
      else if(node.isArray())
      {
        if(value.isArray() && appendValues)
        {
          // Append the new values to the existing ones.
          ArrayNode targetArray = (ArrayNode) node;
          ArrayNode valueArray = (ArrayNode) value;
          for(JsonNode valueNode : valueArray)
          {
            boolean valueFound = false;
            for(JsonNode targetNode : targetArray)
            {
              if(valueNode.equals(targetNode))
              {
                valueFound = true;
                break;
              }
            }
            if(!valueFound)
            {
              targetArray.add(valueNode);
            }
          }
        }
        else
        {
          // Replace the field.
          parent.set(key, value);
        }
      }
      else
      {
        // Replace the field.
        parent.set(key, value);
      }
    }
  }

  private static class PathExistsVisitor extends NodeVisitor
  {
    private boolean pathPresent = false;

    @Override
    JsonNode visitInnerNode(final ObjectNode parent,
                            final Path.Element element) throws ScimException
    {
      JsonNode node = parent.path(element.getAttribute());
      if(node.isArray() && element.getValueFilter() != null)
      {
        return filterArray((ArrayNode) node, element.getValueFilter(), false);
      }
      return node;
    }

    @Override
    void visitLeafNode(final ObjectNode parent,
                       final Path.Element element) throws ScimException
    {
      JsonNode node = parent.path(element.getAttribute());
      if(node.isArray() && element.getValueFilter() != null)
      {
        node = filterArray((ArrayNode) node, element.getValueFilter(), false);
      }

      if(node.isArray())
      {
        if(node.size() > 0)
        {
          setPathPresent(true);
        }
      } else if(! node.isMissingNode())
      {
        setPathPresent(true);
      }
    }

    /**
     * Gets the value of pathPresent.  Path present will be set to
     * true during a traversal if the path was present or false if not.
     *
     * @return returns the value of pathPresent
     */
    public boolean isPathPresent()
    {
      return pathPresent;
    }

    /**
     * Sets the value of pathPresent.
     *
     * @param pathPresent the new value of pathPresent.
     */
    private void setPathPresent(final boolean pathPresent)
    {
      this.pathPresent = pathPresent;
    }
  }

  /**
   * Retrieve all JSON nodes referenced by the provided path. If a path
   * references a JSON array, all nodes the the array will be traversed.
   *
   * @param path The path to the attributes whose values to retrieve.
   * @param node The JSON node representing the SCIM resource.
   *
   * @return List of all JSON nodes referenced by the provided path.
   * @throws ScimException If an error occurs while traversing the JSON node.
   */
  public static List<JsonNode> getValues(final Path path,
                                         final ObjectNode node)
      throws ScimException
  {
    GatheringNodeVisitor visitor = new GatheringNodeVisitor(false);
    traverseValues(visitor, node, 0, path);
    return visitor.values;
  }

  /**
   * Add a new value at the provided path to the provided JSON node. If the path
   * contains any value filters, they will be ignored. The following processing
   * rules are applied depending on the path and value to add:
   *
   * <ul>
   *   <li>
   *     If the path is a root path and targets the core or extension
   *     attributes, the value must be a JSON object containing the
   *     set of attributes to be added to the resource.
   *   </li>
   *   <li>
   *     If the path does not exist, the attribute and value is added.
   *   </li>
   *   <li>
   *     If the path targets a complex attribute (an attribute whose value is
   *     a JSON Object), the value must be a JSON object containing the
   *     set of sub-attributes to be added to the complex value.
   *   </li>
   *   <li>
   *     If the path targets a multi-valued attribute (an attribute whose value
   *     if a JSON Array), the value to add must be a JSON array containing the
   *     set of values to be added to the attribute.
   *   </li>
   *   <li>
   *     If the path targets a single-valued attribute, the existing value is
   *     replaced.
   *   </li>
   *   <li>
   *     If the path targets an attribute that does not exist (has not value),
   *     the attribute is added with the new value.
   *   </li>
   *   <li>
   *     If the path targets an existing attribute, the value is replaced.
   *   </li>
   *   <li>
   *     If the path targets an existing attribute which already contains the
   *     value specified, no changes will be made to the node.
   *   </li>
   * </ul>
   *
   * @param path The path to the attribute.
   * @param node The JSON object node containing the attribute.
   * @param value The value to add.
   * @throws ScimException If an error occurs while traversing the JSON node.
   */
  public static void addValue(final Path path, final ObjectNode node,
                              final JsonNode value) throws ScimException
  {
    UpdatingNodeVisitor visitor = new UpdatingNodeVisitor(value, true);
    traverseValues(visitor, node, 0, path);
  }

  /**
   * Remove the value at the provided path. The following processing
   * rules are applied:
   *
   * <ul>
   *   <li>
   *     If the path targets a single-valued attribute, the attribute and its
   *     associated value is removed.
   *   </li>
   *   <li>
   *     If the path targets a multi-valued attribute and no value filter is
   *     specified, the attribute and all values are removed.
   *   </li>
   *   <li>
   *     If the path targets a multi-valued attribute and a value filter is
   *     specified, the values matched by the filter are removed. If after
   *     removal of the selected values, no other values remain, the
   *     multi-valued attribute is removed.
   *   </li>
   * </ul>
   *
   * @param path The path to the attribute.
   * @param node The JSON object node containing the attribute.
   * @return The list of nodes that were removed.
   * @throws ScimException If an error occurs while traversing the JSON node.
   */
  public static List<JsonNode> removeValues(final Path path,
                                            final ObjectNode node)
      throws ScimException
  {
    GatheringNodeVisitor visitor = new GatheringNodeVisitor(true);
    traverseValues(visitor, node, 0, path);
    return visitor.values;
  }

  /**
   * Update the value at the provided path. The following processing rules are
   * applied:
   *
   * <ul>
   *   <li>
   *     If the path is a root path and targets the core or extension
   *     attributes, the value must be a JSON object containing the
   *     set of attributes to be replaced on the resource.
   *   </li>
   *   <li>
   *     If the path targets a single-valued attribute, the attribute's value
   *     is replaced.
   *   </li>
   *   <li>
   *     If the path targets a multi-valued attribute and no value filter is
   *     specified, the attribute and all values are replaced.
   *   </li>
   *   <li>
   *     If the path targets an attribute that does not exist, treat the
   *     operation as an add.
   *   </li>
   *   <li>
   *     If the path targets a complex attribute (an attribute whose value is
   *     a JSON Object), the value must be a JSON object containing the
   *     set of sub-attributes to be replaced in the complex value.
   *   </li>
   *   <li>
   *     If the path targets a multi-valued attribute and a value filter is
   *     specified that matches one or more values of the multi-valued
   *     attribute, then all matching record values will be replaced.
   *   </li>
   *   <li>
   *     If the path targets a complex multi-valued attribute with a value
   *     filter and a specific sub-attribute
   *     (e.g. "addresses[type eq "work"].streetAddress"), the matching
   *     sub-attribute of all matching records is replaced.
   *   </li>
   *   <li>
   *     If the path targets a multi-valued attribute for which a value filter
   *     is specified and no records match was made, the NoTarget exception
   *     will be thrown.
   *   </li>
   * </ul>
   * @param path The path to the attribute.
   * @param node The JSON object node containing the attribute.
   * @param value The replacement value.
   * @throws ScimException If an error occurs while traversing the JSON node.
   */
  public static void replaceValue(final Path path,
                                  final ObjectNode node,
                                  final JsonNode value) throws ScimException
  {
    UpdatingNodeVisitor visitor = new UpdatingNodeVisitor(value, false);
    traverseValues(visitor, node, 0, path);
  }

  /**
   * Checks for the existence of a path.  This will return true if the
   * path is present (even if the value is null).  This allows the caller
   * to know if the original json string  had something like
   * ... "myPath":null ... rather than just leaving the value out of the
   * json string entirely.
   *
   * @param path The path to the attribute.
   * @param node The JSON object node to search for the path in.
   * @return true if the path has a value set (even if that value is
   * set to null), or false if not.
   * @throws ScimException If an error occurs while traversing the JSON node.
   */
  public static boolean pathExists(final Path path,
                                   final ObjectNode node) throws ScimException
  {
    PathExistsVisitor pathExistsVisitor = new PathExistsVisitor();
    traverseValues(pathExistsVisitor, node, 0, path);
    return pathExistsVisitor.isPathPresent();
  }

  /**
   * Compares two JsonNodes for order. Nodes containing datetime and numerical
   * values are ordered accordingly. Otherwise, the values' string
   * representation will be compared lexicographically.
   *
   * @param n1 the first node to be compared.
   * @param n2 the second node to be compared.
   * @param attributeDefinition The attribute definition of the attribute
   *                            whose values to compare or {@code null} to
   *                            compare string values using case insensitive
   *                            matching.
   * @return a negative integer, zero, or a positive integer as the
   *         first argument is less than, equal to, or greater than the second.
   */
  public static int compareTo(final JsonNode n1, final JsonNode n2,
                              final AttributeDefinition attributeDefinition)
  {
    if (n1.isTextual() && n2.isTextual())
    {
      Date d1 = dateValue(n1);
      Date d2 = dateValue(n2);
      if (d1 != null && d2 != null)
      {
        return d1.compareTo(d2);
      }
      else
      {
        if(attributeDefinition != null &&
            attributeDefinition.getType() == AttributeDefinition.Type.STRING &&
            attributeDefinition.isCaseExact())
        {
          return n1.textValue().compareTo(n2.textValue());
        }
        return StaticUtils.toLowerCase(n1.textValue()).compareTo(
            StaticUtils.toLowerCase(n2.textValue()));
      }
    }

    if (n1.isNumber() && n2.isNumber())
    {
      if(n1.isFloatingPointNumber() || n2.isFloatingPointNumber())
      {
        return Double.compare(n1.doubleValue(), n2.doubleValue());
      }

      return Long.compare(n1.longValue(), n2.longValue());
    }

    // Compare everything else lexicographically
    return n1.asText().compareTo(n2.asText());
  }

  /**
   * Generates a list of patch operations that can be applied to the source
   * node in order to make it match the target node.
   *
   * @param source The source node for which the set of modifications should
   *               be generated.
   * @param target The target node, which is what the source node should
   *               look like if the returned modifications are applied.
   * @param removeMissing Whether to remove fields that are missing in the
   *                      target node.
   * @return A diff with modifications that can be applied to the source
   *         resource in order to make it match the target resource.
   */
  public static List<PatchOperation> diff(
      final ObjectNode source, final ObjectNode target,
      final boolean removeMissing)
  {
    List<PatchOperation> ops = new LinkedList<PatchOperation>();
    ObjectNode targetToAdd = target.deepCopy();
    ObjectNode targetToReplace = target.deepCopy();
    diff(Path.root(), source, targetToAdd, targetToReplace, ops, removeMissing);
    if(targetToReplace.size() > 0)
    {
      ops.add(PatchOperation.replace(null, targetToReplace));
    }
    if(targetToAdd.size() > 0)
    {
      ops.add(PatchOperation.add(null, targetToAdd));
    }
    return ops;
  }

  /**
   * Internal diff that is used to recursively diff source and target object
   * nodes.
   *
   * @param parentPath The path to the source object node.
   * @param source The source node.
   * @param targetToAdd The target node that will be modified to only contain
   *                    the fields to add.
   * @param targetToReplace The target node that will be modified to only
   *                        contain the fields to replace.
   * @param operations The list of operations to append.
   * @param removeMissing Whether to remove fields that are missing in the
   *                      target node.
   */
  private static void diff(final Path parentPath,
                           final ObjectNode source,
                           final ObjectNode targetToAdd,
                           final ObjectNode targetToReplace,
                           final List<PatchOperation> operations,
                           final boolean removeMissing)
  {
    // First iterate through the source fields and compare it to the target
    Iterator<Map.Entry<String, JsonNode>> si = source.fields();
    while (si.hasNext())
    {
      Map.Entry<String, JsonNode> sourceField = si.next();
      Path path = parentPath.attribute(sourceField.getKey());
      JsonNode sourceValue = sourceField.getValue();
      JsonNode targetValueToAdd = targetToAdd.remove(sourceField.getKey());
      JsonNode targetValueToReplace =
          targetToReplace == targetToAdd ? targetValueToAdd :
              targetToReplace.remove(sourceField.getKey());
      if (targetValueToAdd == null)
      {
        if(removeMissing)
        {
          operations.add(PatchOperation.remove(path));
        }
        continue;
      }
      if (sourceValue.getNodeType() == targetValueToAdd.getNodeType())
      {
        // Value present in both and they are of the same type.
        if (sourceValue.isObject())
        {
          // Recursively diff the object node.
          diff(path,
              (ObjectNode) sourceValue, (ObjectNode) targetValueToAdd,
              (ObjectNode) targetValueToReplace, operations, removeMissing);
          // Include the object node if there are fields to add or replace.
          if (targetValueToAdd.size() > 0)
          {
            targetToAdd.set(sourceField.getKey(), targetValueToAdd);
          }
          if (targetValueToReplace.size() > 0)
          {
            targetToReplace.set(sourceField.getKey(), targetValueToReplace);
          }
        } else if (sourceValue.isArray())
        {
          if (targetValueToAdd.size() == 0)
          {
            // Explicitly clear all attribute values.
            operations.add(PatchOperation.remove(path));
          } else
          {
            // Go through each value and try to individually patch them first
            // instead of replacing all values.
            List<PatchOperation> targetOpToRemoveOrReplace =
                new LinkedList<PatchOperation>();
            boolean replaceAllValues = false;
            for(JsonNode sv : sourceValue)
            {
              JsonNode tv = removeMatchingValue(sv,
                  (ArrayNode) targetValueToAdd);
              Filter valueFilter = generateValueFilter(sv);
              if(valueFilter == null)
              {
                replaceAllValues = true;
                Debug.debug(Level.WARNING, DebugType.OTHER,
                    "Performing full replace of target " +
                        "array node " + path + " since the it is not " +
                        "possible to generate a value filter to uniquely " +
                        "identify the value " + sv.toString());
                break;
              }
              Path valuePath = parentPath.attribute(
                  sourceField.getKey(), valueFilter);
              if(tv != null)
              {
                // The value is in both source and target arrays.
                if (sv.isObject() && tv.isObject())
                {
                  // Recursively diff the object node.
                  diff(valuePath, (ObjectNode) sv, (ObjectNode) tv,
                      (ObjectNode) tv, operations, removeMissing);
                  if (tv.size() > 0)
                  {
                    targetOpToRemoveOrReplace.add(
                        PatchOperation.replace(valuePath, tv));
                  }
                }
              }
              else
              {
                targetOpToRemoveOrReplace.add(
                    PatchOperation.remove(valuePath));
              }
            }
            if (!replaceAllValues && targetValueToReplace.size() <=
                targetValueToAdd.size() + targetOpToRemoveOrReplace.size())
            {
              // We are better off replacing the entire array.
              Debug.debug(Level.INFO, DebugType.OTHER,
                  "Performing full replace of target " +
                      "array node " + path + " since the " +
                      "array (" + targetValueToReplace.size() + ") " +
                      "is smaller than removing and " +
                      "replacing (" + targetOpToRemoveOrReplace.size() + ") " +
                      "then adding (" + targetValueToAdd.size() + ")  " +
                      "the values individually");
              replaceAllValues = true;
              targetToReplace.set(sourceField.getKey(), targetValueToReplace);

            }
            if(replaceAllValues)
            {
              targetToReplace.set(sourceField.getKey(), targetValueToReplace);
            }
            else
            {
              if (!targetOpToRemoveOrReplace.isEmpty())
              {
                operations.addAll(targetOpToRemoveOrReplace);
              }
              if (targetValueToAdd.size() > 0)
              {
                targetToAdd.set(sourceField.getKey(), targetValueToAdd);
              }
            }
          }
        } else
        {
          // They are value nodes.
          if (!sourceValue.equals(targetValueToAdd))
          {
            // Just replace with the target value.
            targetToReplace.set(sourceField.getKey(), targetValueToReplace);
          }
        }
      } else
      {
        // Value parent in both but they are of different types.
        if (targetValueToAdd.isNull() ||
            (targetValueToAdd.isArray() && targetValueToAdd.size() == 0))
        {
          // Explicitly clear attribute value.
          operations.add(PatchOperation.remove(path));
        } else
        {
          // Just replace with the target value.
          targetToReplace.set(sourceField.getKey(), targetValueToReplace);
        }
      }
    }

    if(targetToAdd != targetToReplace)
    {
      // Now iterate through the fields in targetToReplace and remove any that
      // are not in the source. These new fields should only be in targetToAdd
      Iterator<String> ri = targetToReplace.fieldNames();
      while (ri.hasNext())
      {
        if (!source.has(ri.next()))
        {
          ri.remove();
        }
      }
    }
  }

  /**
   * Removes the value from an ArrayNode that matches the provided node.
   *
   * @param sourceValue The sourceValue node to match.
   * @param targetValues The ArrayNode containing the values to remove from.
   * @return The matching value that was removed or {@code null} if no matching
   *         value was found.
   */
  private static JsonNode removeMatchingValue(final JsonNode sourceValue,
                                              final ArrayNode targetValues)
  {
    if(sourceValue.isObject())
    {
      // Find a target value that has the most fields in common with the source
      // and have identical values. Common fields that are also one of the
      // SCIM standard multi-value sub-attributes (ie. type, value, etc...) have
      // a higher weight when determining the best matching value.
      TreeMap<Integer, Integer> matchScoreToIndex =
          new TreeMap<Integer, Integer>();
      for(int i = 0; i < targetValues.size(); i++)
      {
        JsonNode targetValue = targetValues.get(i);
        if(targetValue.isObject())
        {
          int matchScore = 0;
          Iterator<String> si = sourceValue.fieldNames();
          while(si.hasNext())
          {
            String field = si.next();
            if(sourceValue.get(field).equals(targetValue.path(field)))
            {
              if(field.equals("value") || field.equals("$ref"))
              {
                // These fields have the highest chance of having unique values.
                matchScore += 3;
              }
              else if(field.equals("type") || field.equals("display"))
              {
                // These fields should mostly be unique.
                matchScore += 2;
              }
              else if(field.equals("primary"))
              {
                // This field will definitely not be unique.
                matchScore += 0;
              }
              else
              {
                // Not one of the normative fields. Use the default weight.
                matchScore += 1;
              }
            }
          }
          if(matchScore > 0)
          {
            matchScoreToIndex.put(matchScore, i);
          }
        }
      }
      if(!matchScoreToIndex.isEmpty())
      {
        return targetValues.remove(matchScoreToIndex.lastEntry().getValue());
      }
    }
    else
    {
      // Find an exact match
      for(int i = 0; i < targetValues.size(); i++)
      {
        if(sourceValue.equals(targetValues.get(i)))
        {
          return targetValues.remove(i);
        }
      }
    }

    // Can't find a match at all.
    return null;
  }

  /**
   * Generate a value filter that may be used to uniquely identify this value
   * in an array node.
   *
   * @param value The value to generate a filter from.
   * @return The value filter or {@code null} if a value filter can not be used
   *         to uniquely identify the node.
   */
  private static Filter generateValueFilter(final JsonNode value)
  {
    if (value.isValueNode())
    {
      // Use the implicit "value" sub-attribute to reference this value.
      return Filter.eq(Path.root().attribute("value"), (ValueNode) value);
    }
    if (value.isObject())
    {
      List<Filter> filters = new ArrayList<Filter>(value.size());
      Iterator<Map.Entry<String, JsonNode>> fieldsIterator = value.fields();
      while (fieldsIterator.hasNext())
      {
        Map.Entry<String, JsonNode> field = fieldsIterator.next();
        if (!field.getValue().isValueNode())
        {
          // We can't nest value filters.
          return null;
        }
        filters.add(Filter.eq(Path.root().attribute(field.getKey()),
            (ValueNode) field.getValue()));
      }
      return Filter.and(filters);
    }

    // We can't uniquely identify this value with a filter.
    return null;
  }

  /**
   * Try to parse out a date from a JSON text node.
   *
   * @param node The JSON node to parse.
   *
   * @return A parsed date instance or {@code null} if the text is not an
   * ISO8601 formatted date and time string.
   */
  private static Date dateValue(final JsonNode node)
  {
    String text = node.textValue().trim();
    if (text.length() >= 19 &&
        Character.isDigit(text.charAt(0)) &&
        Character.isDigit(text.charAt(1)) &&
        Character.isDigit(text.charAt(2)) &&
        Character.isDigit(text.charAt(3)) &&
        text.charAt(4) == '-')
    {
      try
      {
        return ISO8601Utils.parse(text, new ParsePosition(0));
      }
      catch (ParseException e)
      {
        // This is not a date after all.
      }
    }
    return null;
  }

  /**
   * Internal method to recursively gather values based on path.
   *
   * @param nodeVisitor The NodeVisitor to use to handle the traversed nodes.
   * @param node The JSON node representing the SCIM resource.
   * @param index The index to the current path element.
   * @param path The path to the attributes whose values to retrieve.
   *
   * @throws ScimException If an error occurs while traversing the JSON node.
   */
  private static void traverseValues(final NodeVisitor nodeVisitor,
                                     final ObjectNode node,
                                     final int index,
                                     final Path path)
      throws ScimException
  {
    Path.Element element = path.size() == 0 ? null : path.getElement(index);
    if(index < path.size() - 1)
    {
      JsonNode child = nodeVisitor.visitInnerNode(node, element);
      if(child.isArray())
      {
        for(JsonNode value : child)
        {
          if(value.isObject())
          {
            traverseValues(nodeVisitor, (ObjectNode)value, index + 1, path);
          }
        }
      }
      else if(child.isObject())
      {
        traverseValues(nodeVisitor, (ObjectNode)child, index + 1, path);
      }
    }
    else
    {
      nodeVisitor.visitLeafNode(node, element);
    }
  }

  /**
   * Factory method for constructing a SCIM compatible Jackson
   * {@link ObjectReader} with default settings. Note that the resulting
   * instance is NOT usable as is, without defining expected value type with
   * ObjectReader.forType.
   *
   * @return A Jackson {@link ObjectReader} with default settings.
   */
  public static ObjectReader getObjectReader()
  {
    return SDK_OBJECT_MAPPER.reader();
  }

  /**
   * Factory method for constructing a SCIM compatible Jackson
   * {@link ObjectWriter} with default settings.
   *
   * @return A Jackson {@link ObjectWriter} with default settings.
   */
  public static ObjectWriter getObjectWriter()
  {
    return SDK_OBJECT_MAPPER.writer();
  }

  /**
   * Retrieve the SCIM compatible Jackson JsonNodeFactory that may be used
   * to create tree model JsonNode instances.
   *
   * @return The Jackson JsonNodeFactory.
   */
  public static JsonNodeFactory getJsonNodeFactory()
  {
    return SDK_OBJECT_MAPPER.getNodeFactory();
  }

  /**
   * Utility method to convert a POJO to Jackson tree model. This behaves
   * exactly the same as Jackson's ObjectMapper.valueToTree.
   *
   * @param <T> Actual node type.
   * @param fromValue POJO to convert.
   * @return converted JsonNode.
   */
  public static <T extends JsonNode> T valueToTree(final Object fromValue)
  {
    return SDK_OBJECT_MAPPER.valueToTree(fromValue);
  }

  /**
   * Creates an configured SCIM compatible Jackson ObjectMapper. Creating new
   * ObjectMapper instances are expensive so instances should be shared if
   * possible. Alternatively, consider using one of the getObjectReader,
   * getObjectWriter, getJsonNodeFactory, or valueToTree methods which uses the
   * SDK's ObjectMapper singleton.
   *
   * @return an Object Mapper with the correct options set for seirializing
   *     and deserializing SCIM JSON objects.
   */
  public static ObjectMapper createObjectMapper()
  {
    ObjectMapper mapper = new ObjectMapper();

    mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    mapper.configure(SerializationFeature.WRITE_NULL_MAP_VALUES, false);
    mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    mapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
    mapper.setNodeFactory(new ScimJsonNodeFactory());
    return mapper;
  }
}