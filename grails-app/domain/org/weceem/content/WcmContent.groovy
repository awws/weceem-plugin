/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.weceem.content

import org.grails.taggable.*

/**
 * WcmContent class describes the content information.
 *
 * @author Stephan Albers
 * @author July Karpey
 * @author Sergei Shushkevich
 * @author Marc Palmer
 */
// @todo this SHOULD be abstract, but will it work?
class WcmContent implements Comparable, Taggable {
    
    static VALID_ALIAS_URI_CHARS = 'A-Za-z0-9_\\-\\.'
    static INVALID_ALIAS_URI_CHARS_PATTERN = "[^"+VALID_ALIAS_URI_CHARS+"]"
    static VALID_ALIAS_URI_PATTERN = "["+VALID_ALIAS_URI_CHARS+"]+"
    
    transient def wcmSecurityService
    
    // we only index title and space
    static searchable = {
         alias WcmContent.name.replaceAll("\\.", '_')
         
         only = ['title', 'status', 'absoluteURI']
         
         absoluteURI excludeFromAll: true
         space component: true 
         status component: [prefix:'status_']
    }
    
    public static icon = [plugin: "weceem", dir: "_weceem/images/weceem", file: "virtual-page.png"]
    
    // that is also the subject for forums
    String title

    // alias of the title aka "page slug"
    // by default - first 30 "escaped" chars of title
    String aliasURI

    Integer orderIndex
    SortedSet children

    String language
    
    String createdBy
    Date createdOn
    String changedBy
    Date changedOn
    
    Date publishFrom = new Date() // default to now
    Date publishUntil
    
    WcmStatus status
    
    static belongsTo = [space: WcmSpace, parent: WcmContent]
    static transients = [ 
        'titleForHTML', 
        'titleForMenu', 
        'versioningProperties', 
        'contentAsText', 
        'contentAsHTML', 
        'mimeType', 
        'wcmSecurityService',
        'absoluteURI'
    ]
    static hasMany = [children: WcmContent]
    static hasOne = [parent: WcmContent]

    static constraints = {
        title(size:1..100, nullable: false, blank: false)
        aliasURI(nullable: false, blank: false, unique: ['space', 'parent'], maxSize: 50)
        space(nullable: false)
        status(nullable: false)
        orderIndex(nullable: true)
        parent(nullable: true, lazy:true)
        createdBy(nullable: true)
        createdOn(nullable: true)
        changedBy(nullable: true)
        changedOn(nullable: true)
        publishFrom(nullable: false)
        publishUntil(nullable: true, validator: { value, obj -> 
            // Allow it to be null or greater than publishFrom
            if (value != null) {
                if (value.time <= (obj.publishFrom ? obj.publishFrom.time : value.time-1)) {
                    return "content.publish.until.must.be.in.future"
                }
            }
            return null
        })
        language(nullable: true, size:0..3)
    }

    static mapping = {
        cache usage: 'read-write', include: 'non-lazy'
        createdOn index:'content_createdOn_Idx'
        changedOn index:'content_changedOn_Idx'
        title index:'content_contentName_Idx'
        aliasURI index:'content_aliasURI_Idx'
        space index:'content_space_Idx', lazy: false // we never want proxies for this
        children cascade:"all", lazy: true
        status lazy:false
    }

    static editors = {
        space()
        title()
        status()
        aliasURI(group:'extra')
        language(group:'extra', editor:'LanguageList')

        orderIndex hidden:true
        createdBy editor:'ReadOnly', group:'extra'
        createdOn editor:'ReadOnlyDate', group:'extra'
        changedBy editor:'ReadOnly', group:'extra'
        changedOn editor:'ReadOnlyDate', group:'extra'
        publishFrom group:'extra'
        publishUntil group:'extra'
        tags editor:'Tags', group:'extra'
        parent hidden:true
        children hidden:true
    }
    
    int compareTo(Object o) {
        if (this.is(o)) return 0
        
        // NOTE: the orderIndex == a.orderIndex returning -1 is REQUIRED with Grails 1.1.1 to workaround
        // issues where orderIndex for children is not unique - returning 0 stops a node being returned in the set!
        if (!o || (o.orderIndex == null) || (o.orderIndex == orderIndex)) return -1
        this.orderIndex?.compareTo(o.orderIndex)
    }

    Boolean canHaveChildren() { true }

    Boolean canHaveMultipleParents() { true }
    
    String getMimeType() { "text/plain" }
    
    /**
     * Can be overriden by content types to customize the short title used for rendering menu items etc
     */
    public String getTitleForMenu() { title }

    /**
     * Can be overriden by content types to customize the long title used for rendering HTML SEO-friendly page titles
     */
    public String getTitleForHTML() { title }

    /**
     * Must be overriden by content types that can represent their content as text.
     * Used for search results and versioning
     */
    public String getContentAsText() { "" }

    /**
     * Should be overriden by content types that can represent their content as HTML.
     * Used for wcm:content tag (content rendering)
     */
    public String getContentAsHTML() { contentAsText ? contentAsText.encodeAsHTML() : '' }

    /** 
     * Descendents must override and call super and add their own properties
     */
    public Map getVersioningProperties() { 
        def t = this
        [
            title:t.title,
            aliasURI:t.aliasURI,
            orderIndex:t.orderIndex,
            space:t.space?.name, // Hmm what makes sense here if moved?
            parent:t.parent?.ident(), // Hmm what makes sense here if moved?
            createdBy:t.createdBy,
            createdOn:t.createdOn,
            changedBy:t.changedBy,
            changedOn:t.changedOn
        ] 
    }

    public void createAliasURI(parent) {
        def uri = (title.size() < 30) ? title : title.substring(0, 30)
        aliasURI = uri.replaceAll(INVALID_ALIAS_URI_CHARS_PATTERN, '-')
    }

    public String getAbsoluteURI() {
        def uri = new StringBuilder()
        uri << aliasURI
        def c = this
        while (c.parent != null) {
            c = c.parent
            uri.insert(0,'/')
            uri.insert(0,c.aliasURI)
        }
        return uri.toString()
    }
    
    def beforeInsert = {
        def by = wcmSecurityService?.userName
        if (by == null) by = "system"
        //assert by != null
        
        if (createdBy == null) createdBy = by
        if (createdOn == null) createdOn = new Date()
    }   

    def beforeUpdate = {
        // @todo check, its possible should be using 'delegate' here not normal property access
        
        changedOn = new Date()
        
        changedBy = wcmSecurityService?.userName
        if (changedBy == null) {
            changedBy = "system"
        }
    }

    void saveRevision(def latestTitle, def latestSpaceName) {
        def self = this 
        
        def t = this
        
        def criteria = WcmContentVersion.createCriteria()
        def lastRevision = criteria.get {
            eq('objectKey', ident())
            projections {
                max('revision')
            }
        }
        
        // Ask the content instance what we should serialize in a version
        def verProps = getVersioningProperties()
        
        def output = new StringBuilder()
        output << "<revision>"
        verProps.each { vp ->
            def propName = vp.key
            def propValue = vp.value
            if (propValue) {
                output << "<${propName}>${propValue.encodeAsHTML()}</${propName}>"
            }
        }
        
        output << "<content>${getContentAsText().encodeAsHTML()}</content>"
        output << "</revision>"

        def xml = output.toString()
        def cv = new WcmContentVersion(objectKey: ident(),
                revision: (lastRevision ? lastRevision + 1 : 1),
                objectClassName: self.class.name,
                objectContent: xml,
                contentTitle: latestTitle,
                spaceName: latestSpaceName,
                createdBy: createdBy,
                createdOn: createdOn)

        cv.updateRevisions()
        if (!cv.save()) {
            log.error "In createVersion of ${this}, save failed: ${cv.errors}"
            assert false
        }
    }
}