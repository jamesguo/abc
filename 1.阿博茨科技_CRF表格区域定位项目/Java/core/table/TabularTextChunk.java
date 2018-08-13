package com.abcft.pdfextract.core.table;

import com.abcft.pdfextract.core.model.RectangularTextContainer;
import com.abcft.pdfextract.core.model.Rectangle;
import com.abcft.pdfextract.core.model.TextElement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.text.Normalizer;

@SuppressWarnings("serial")
@Deprecated
public class TabularTextChunk extends RectangularTextContainer<TextElement> {

    private int textBlockId;
    final List<TextElement> textElements = new ArrayList<>();
    
    public TabularTextChunk(float left, float top, float width, float height) {
        super(left, top, width, height);
    }
    
    public TabularTextChunk(TextElement textElement) {
        super(textElement.x, textElement.y, textElement.width, textElement.height);
        this.addElement(textElement);
        this.textBlockId = textElement.getGroupIndex();
    }
    
    public TabularTextChunk(List<TextElement> textElements) {
        this(textElements.get(0));
        for (int i = 1; i < textElements.size(); i++) {
            this.addElement(textElements.get(i));
        }
    }

    private enum DirectionalityOptions {
        LTR, NONE, RTL
    }

    // I hate Java so bad.
    // we're making this HashMap static! which requires really funky initialization per http://stackoverflow.com/questions/6802483/how-to-directly-initialize-a-hashmap-in-a-literal-way/6802502#6802502
    private static HashMap<Byte, DirectionalityOptions> directionalities;
    static
    {
        directionalities = new HashMap<>();
        // BCT = bidirectional character type
        directionalities.put(Character.DIRECTIONALITY_ARABIC_NUMBER, DirectionalityOptions.LTR);               // Weak BCT    "AN" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_BOUNDARY_NEUTRAL, DirectionalityOptions.NONE);            // Weak BCT    "BN" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR, DirectionalityOptions.LTR);     // Weak BCT    "CS" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_EUROPEAN_NUMBER, DirectionalityOptions.LTR);             // Weak BCT    "EN" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR, DirectionalityOptions.LTR);   // Weak BCT    "ES" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR, DirectionalityOptions.LTR);  // Weak BCT    "ET" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_LEFT_TO_RIGHT, DirectionalityOptions.LTR);              // Strong BCT  "L" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING, DirectionalityOptions.LTR);     // Strong BCT  "LRE" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE, DirectionalityOptions.LTR);      // Strong BCT  "LRO" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_NONSPACING_MARK, DirectionalityOptions.NONE);             // Weak BCT    "NSM" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_OTHER_NEUTRALS, DirectionalityOptions.NONE);              // Neutral BCT "ON" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_PARAGRAPH_SEPARATOR, DirectionalityOptions.NONE);         // Neutral BCT "B" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_POP_DIRECTIONAL_FORMAT, DirectionalityOptions.NONE);      // Weak BCT    "PDF" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_RIGHT_TO_LEFT, DirectionalityOptions.RTL);              // Strong BCT  "R" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC, DirectionalityOptions.RTL);       // Strong BCT  "AL" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING, DirectionalityOptions.RTL);    // Strong BCT  "RLE" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE, DirectionalityOptions.RTL);     // Strong BCT  "RLO" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_SEGMENT_SEPARATOR, DirectionalityOptions.RTL);          // Neutral BCT "S" in the Unicode specification.
        directionalities.put(Character.DIRECTIONALITY_UNDEFINED, DirectionalityOptions.NONE);                   // Undefined BCT.
        directionalities.put(Character.DIRECTIONALITY_WHITESPACE, DirectionalityOptions.NONE);                  // Neutral BCT "WS" in the Unicode specification.
    }

    /** Splits a TextChunk into N TextChunks, where each chunk is of a single directionality, and
        then reverse the RTL ones.
        what we're doing here is *reversing* the Unicode bidi algorithm
        in the language of that algorithm, each chunk is a (maximal) directional run.
        We attach whitespace to the beginning of non-RTL
    **/
    public TabularTextChunk groupByDirectionality(Boolean isLtrDominant) {
        if (this.getElements().size() <= 0) {
            throw new IllegalArgumentException();
        }

        ArrayList<ArrayList<TextElement>> chunks = new ArrayList<>();
        ArrayList<TextElement> buff = new ArrayList<>();
        DirectionalityOptions buffDirectionality = DirectionalityOptions.NONE; // the directionality of the characters in buff;

        for(TextElement te: this.getElements()){
            //TODO: we need to loop over the textelement characters
            //      because it is possible for a textelement to contain multiple characters?


            // System.out.println(te.getText() + " is " + Character.getDirectionality(te.getText().charAt(0) ) + " " + directionalities.get(Character.getDirectionality(te.getText().charAt(0) )));
            if(buff.size() == 0){
                buff.add(te);
                buffDirectionality = directionalities.get(Character.getDirectionality(te.getText().charAt(0)));
            }else{
                if(buffDirectionality == DirectionalityOptions.NONE){
                    buffDirectionality = directionalities.get(Character.getDirectionality(te.getText().charAt(0)));
                }
                DirectionalityOptions teDirectionality = directionalities.get(Character.getDirectionality(te.getText().charAt(0)));

                if(teDirectionality == buffDirectionality || teDirectionality == DirectionalityOptions.NONE) {
                    if ( Character.getDirectionality(te.getText().charAt(0) ) == Character.DIRECTIONALITY_WHITESPACE && (buffDirectionality == (isLtrDominant ? DirectionalityOptions.RTL : DirectionalityOptions.LTR) ) ){
                        buff.add(0, te);
                    }else{
                        buff.add(te);
                    }
                }else{
                    // finish this chunk
                    if (buffDirectionality == DirectionalityOptions.RTL){
                        Collections.reverse(buff);
                    }
                    chunks.add(buff);

                    // and start a new one
                    buffDirectionality = directionalities.get(Character.getDirectionality(te.getText().charAt(0)));
                    buff = new ArrayList<>();
                    buff.add(te);
                }
            }
        }
        if (buffDirectionality == DirectionalityOptions.RTL){
            Collections.reverse(buff);
        }
        chunks.add(buff);
        ArrayList<TextElement> everything = new ArrayList<>();
        if(!isLtrDominant){
            Collections.reverse(chunks);
        }
        for(ArrayList<TextElement> group : chunks){
            everything.addAll(group);
        }
        return new TabularTextChunk(everything);
    }

    public int isLtrDominant(){
        int ltrCnt = 0;
        int rtlCnt = 0;
        for (int i = 0; i < this.getElements().size(); i++)
        {
            String elementText = this.getElements().get(i).getText();
            for (int j=0; j<elementText.length();j++){
                byte dir = Character.getDirectionality( elementText.charAt(j) );
                if ((dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT ) ||
                        (dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING) ||
                        (dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE ))
                {
                    ltrCnt++;
                }
                else if ((dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT ) ||
                        (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) ||
                        (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING) ||
                        (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE ))
                {
                    rtlCnt++;
                }
            }
        }
        return intCompare(ltrCnt, rtlCnt); // 1 is LTR, 0 is neutral, -1 is RTL
    }

    public static int intCompare(int x, int y) {
        return (x < y) ? -1 : ((x == y) ? 0 : 1);
    }


    public TabularTextChunk merge(TabularTextChunk other) {
        super.merge(other);
        return this;
    }

    public void addElement(TextElement textElement) {
        this.textElements.add(textElement);
        if (textElement.getGroupIndex() != this.textBlockId) {
            this.textBlockId = -1;
        }
        this.merge(textElement);
    }
    
    public void addElements(List<TextElement> textElements) {
        for (TextElement te: textElements) {
            this.addElement(te);
        }
    }

    public List<TextElement> getElements() {
        return textElements;
    }

    public int getTextBlockId() {
        return textBlockId;
    }

    @Override
    public String getText(boolean useLineReturns) {
        // TODO Handle useLineReturns
        if (this.textElements.size() == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (TextElement te: this.textElements) {
            sb.append(te.getText());
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFKC).trim();
    }

    
    /**
     * Returns true if text contained in this TextChunk is the same repeated character
     */
    public boolean isSameChar(Character c) {
        return isSameChar(new Character[] { c });
    }
    
    public boolean isSameChar(Character[] c) {
        String s = this.getText();
        List<Character> chars = Arrays.asList(c);
        for (int i = 0; i < s.length(); i++) {
            if (!chars.contains(s.charAt(i))) { return false; }
        }
        return true;
    }
    
    /** Splits a TextChunk in two, at the position of the i-th TextElement
     */
    public TabularTextChunk[] splitAt(int i) {
        if (i < 1 || i >= this.getElements().size()) {
            throw new IllegalArgumentException();
        }
        
        TabularTextChunk[] rv = new TabularTextChunk[] {
                new TabularTextChunk(this.getElements().subList(0, i)),
                new TabularTextChunk(this.getElements().subList(i, this.getElements().size()))
        };
        return rv;
    }
    
    /**
     * Removes runs of identical TextElements in this TextChunk
     * For example, if the TextChunk contains this string of characters: "1234xxxxx56xx"
     * and c == 'x' and minRunLength == 4, this method will return a list of TextChunk
     * such that: ["1234", "56xx"]
     */
    public List<TabularTextChunk> squeeze(Character c, int minRunLength) {
        Character currentChar, lastChar = null;
        int subSequenceLength = 0, subSequenceStart = 0;
        TabularTextChunk[] t;
        List<TabularTextChunk> rv = new ArrayList<>();
        
        for (int i = 0; i < this.getElements().size(); i++) {
            TextElement textElement = this.getElements().get(i);
            currentChar = textElement.getText().charAt(0); 
            if (lastChar != null && currentChar.equals(c) && lastChar.equals(currentChar)) {
                subSequenceLength++;
            }
            else {
                if (((lastChar != null && !lastChar.equals(currentChar)) || i + 1 == this.getElements().size()) && subSequenceLength >= minRunLength) {

                    if (subSequenceStart == 0 && subSequenceLength <= this.getElements().size() - 1) {
                        t = this.splitAt(subSequenceLength);
                    }
                    else {
                        t = this.splitAt(subSequenceStart);
                        rv.add(t[0]);
                    }
                    rv.addAll(t[1].squeeze(c, minRunLength)); // Lo and behold, recursion.
                    break;

                }
                subSequenceLength = 1;
                subSequenceStart = i;
            }
            lastChar = currentChar;
        }
        
        
        if (rv.isEmpty()) { // no splits occurred, hence this.squeeze() == [this]
            if (subSequenceLength >= minRunLength && subSequenceLength < this.textElements.size()) {
                TabularTextChunk[] chunks = this.splitAt(subSequenceStart);
                rv.add(chunks[0]);
            }
            else {
                rv.add(this);
            }
        }
        
        return rv;

    }
    
    
    
    @Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result
				+ ((textElements == null) ? 0 : textElements.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		TabularTextChunk other = (TabularTextChunk) obj;
		if (textElements == null) {
			if (other.textElements != null)
				return false;
		} else if (!textElements.equals(other.textElements))
			return false;
		return true;
	}

    @Override
    public String toString() {
        String s = super.toString();
        Class<?> clazz = getClass();
        return s.replace(clazz.getName(), clazz.getSimpleName());
    }

    public static boolean allSameChar(List<TabularTextChunk> textChunks) {
        /* the previous, far more elegant version of this method failed when there was an empty TextChunk in textChunks.
         * so I rewrote it in an ugly way. but it works!
         * it would be good for this to get rewritten eventually
         * the purpose is basically just to return true iff there are 2+ TextChunks and they're identical.
         * -Jeremy 5/13/2016
         */

        if(textChunks.size() == 1) return false;
        boolean hasHadAtLeastOneNonEmptyTextChunk = false;
        char first = '\u0000';
        for (TabularTextChunk tc: textChunks) {
            if (tc.getText().length() == 0) {
                continue;
            }
            if (first == '\u0000'){
                first = tc.getText().charAt(0);
            }else{
                hasHadAtLeastOneNonEmptyTextChunk = true;
                if (!tc.isSameChar(first)) return false;
            }
        }
        return hasHadAtLeastOneNonEmptyTextChunk;
    }
    
    public static List<TabularLine> groupByLines(List<TabularTextChunk> textChunks) {
        List<TabularLine> lines = new ArrayList<>();

        if (textChunks.size() == 0) {
            return lines;
        }

        float bbwidth = Rectangle.boundingBoxOf(textChunks).width;
        
        TabularLine l = new TabularLine();
        l.addTextChunk(textChunks.get(0));
        textChunks.remove(0);
        lines.add(l);

        TabularLine last = lines.get(lines.size() - 1);
        for (TabularTextChunk te: textChunks) {
            if (last.verticalOverlapRatio(te) < 0.1) {
                if (last.width / bbwidth > 0.9 && TabularTextChunk.allSameChar(last.getTextElements())) {
                    lines.remove(lines.size() - 1);
                }
                lines.add(new TabularLine());
                last = lines.get(lines.size() - 1);
            }
            last.addTextChunk(te);
        }
        
        if (last.width / bbwidth > 0.9 && TabularTextChunk.allSameChar(last.getTextElements())) {
            lines.remove(lines.size() - 1);
        }
        
        List<TabularLine> rv = new ArrayList<>(lines.size());
        
        for (TabularLine line: lines) {
            rv.add(TabularLine.removeRepeatedCharacters(line, ' ', 3));
        }
        
        return rv;
    }

}
